package com.cluehelper.player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.VarbitComposition;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Keeps an up-to-date {@link PlayerSnapshot} (PLAN §4 item 3). Every event handler runs on the client
 * thread; changes only mark the model dirty, and the snapshot is rebuilt at most once per tick, from
 * the plugin's GameTick. Anything off the client thread reads snapshots only.
 */
@Slf4j
@Singleton
public class PlayerModel
{
	private static final Set<Integer> RUNE_POUCHES = Set.of(
		ItemID.BH_RUNE_POUCH, ItemID.BH_RUNE_POUCH_TROUVER, ItemID.DIVINE_RUNE_POUCH, ItemID.DIVINE_RUNE_POUCH_TROUVER);
	private static final int[] POUCH_TYPES = {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2,
		VarbitID.RUNE_POUCH_TYPE_3, VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6};
	private static final int[] POUCH_QUANTITIES = {VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2,
		VarbitID.RUNE_POUCH_QUANTITY_3, VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5,
		VarbitID.RUNE_POUCH_QUANTITY_6};
	/** The cooldown varps hold the epoch minute the cooldown started (Shortest Path's {@code @} clauses). */
	private static final List<Integer> OWN_VARPS = List.of(VarPlayerID.QP, VarPlayerID.AIDE_TELE_TIMER,
		VarPlayerID.SLUG2_REGIONUID);
	private static final Set<WorldType> DORMANT_WORLDS = EnumSet.of(WorldType.SEASONAL, WorldType.DEADMAN,
		WorldType.BETA_WORLD, WorldType.TOURNAMENT_WORLD, WorldType.QUEST_SPEEDRUNNING, WorldType.LAST_MAN_STANDING,
		WorldType.PVP_ARENA, WorldType.NOSAVE_MODE);

	private final Client client;
	private final ChargeReader chargeReader;
	private final List<Consumer<PlayerSnapshot>> listeners = new CopyOnWriteArrayList<>();
	private final Queue<int[]> pendingWatches = new ConcurrentLinkedQueue<>();
	private final RebuildStats stats = new RebuildStats();

	private final Set<Integer> watchedVarbits = new HashSet<>();
	private final Set<Integer> watchedVarps = new HashSet<>();
	/** The varps that hold a watched varbit or are watched themselves: a change to one rebuilds. */
	private final Set<Integer> watchedVarpIndices = new HashSet<>();
	private Map<Quest, QuestState> quests = Map.of();
	private boolean questsRead;
	private volatile boolean questsDirty;
	private volatile boolean dirty;
	private String dirtyBecause;
	private long version;

	private volatile RuneModel runeModel;
	private volatile PlayerSnapshot snapshot;
	private volatile Position position = Position.NONE;

	@Inject
	PlayerModel(Client client, ChargeReader chargeReader)
	{
		this.client = client;
		this.chargeReader = chargeReader;
	}

	public void start()
	{
		List<Integer> varbits = new ArrayList<>(List.of(VarbitID.SPELLBOOK, VarbitID.LUNAR_SPELLBOOK_CHANGE,
			VarbitID.SB_SWAP_CHECK, VarbitID.ARCEUUS_SPELLBOOK_UNLOCKED, VarbitID.IRONMAN,
			VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE));
		for (int i = 0; i < POUCH_TYPES.length; i++)
		{
			varbits.add(POUCH_TYPES[i]);
			varbits.add(POUCH_QUANTITIES[i]);
		}
		varbits.addAll(ChargeReader.chargeVarbits());
		watch(varbits, OWN_VARPS);
		markDirty("start");
		questsDirty = true;
	}

	public void stop()
	{
		snapshot = null;
		position = Position.NONE;
	}

	/** The newest snapshot; null while logged out. */
	public PlayerSnapshot getSnapshot()
	{
		return snapshot;
	}

	/**
	 * Where the player is now, read every tick. A snapshot copies it only when its kind or Wilderness level
	 * changes, so walking doesn't make a new snapshot version.
	 */
	public Position getPosition()
	{
		return position;
	}

	/** Null until the first tick after login, when it is read from the cache. */
	public RuneModel getRuneModel()
	{
		return runeModel;
	}

	public RebuildStats getStats()
	{
		return stats;
	}

	/** Listeners run on the client thread, after each rebuild. */
	public Runnable addListener(Consumer<PlayerSnapshot> listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** Adds vars the snapshot copies from now on (catalog rows' Varbits and VarPlayers). Any thread. */
	public void watch(Collection<Integer> varbits, Collection<Integer> varps)
	{
		for (int id : varbits)
		{
			pendingWatches.add(new int[]{0, id});
		}
		for (int id : varps)
		{
			pendingWatches.add(new int[]{1, id});
		}
	}

	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			questsDirty = true;
			markDirty("login");
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			snapshot = null;
			position = Position.NONE;
			questsRead = false;
			quests = Map.of();
		}
	}

	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INV || event.getContainerId() == InventoryID.WORN)
		{
			markDirty("items");
		}
	}

	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarpId() == VarPlayerID.QP)
		{
			questsDirty = true;
		}
		if (watchedVarpIndices.contains(event.getVarpId()))
		{
			markDirty("vars");
		}
	}

	public void onStatChanged()
	{
		markDirty("stats");
	}

	/** From the plugin's GameTick handler. */
	public void onGameTick()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}
		if (runeModel == null)
		{
			runeModel = timed("rune model", () -> RuneModel.load(new ClientCacheView(client)));
			markDirty("rune model");
		}
		resolveWatches();
		Position now = readPosition();
		PlayerSnapshot last = snapshot;
		if (last == null || now.getKind() != last.getPosition().getKind()
			|| wildernessLevel(now) != last.getWildernessLevel())
		{
			markDirty("place");
		}
		position = now;
		if (questsDirty)
		{
			questsDirty = false;
			quests = timed("quests", this::readQuests);
			questsRead = true;
			markDirty("quests");
		}
		if (dirty)
		{
			dirty = false;
			long started = System.nanoTime();
			PlayerSnapshot built = build(now);
			stats.record(dirtyBecause, System.nanoTime() - started, client.getTickCount());
			snapshot = built;
			listeners.forEach(listener -> listener.accept(built));
		}
	}

	private void markDirty(String because)
	{
		if (!dirty)
		{
			dirtyBecause = because;
		}
		dirty = true;
	}

	private void resolveWatches()
	{
		int[] watch;
		boolean added = false;
		while ((watch = pendingWatches.poll()) != null)
		{
			int id = watch[1];
			if (watch[0] == 0 && watchedVarbits.add(id))
			{
				VarbitComposition varbit = client.getVarbit(id);
				if (varbit != null)
				{
					watchedVarpIndices.add(varbit.getIndex());
				}
				added = true;
			}
			else if (watch[0] == 1 && watchedVarps.add(id))
			{
				watchedVarpIndices.add(id);
				added = true;
			}
		}
		if (added)
		{
			markDirty("watch");
		}
	}

	private Position readPosition()
	{
		Player player = client.getLocalPlayer();
		WorldView topLevel = client.getTopLevelWorldView();
		WorldView view = player.getWorldView();
		boolean onBoat = view != null && view.getId() != WorldView.TOPLEVEL;
		if (onBoat)
		{
			WorldEntity boat = topLevel.worldEntities().byIndex(view.getId());
			WorldPoint boatTile = boat == null ? null : WorldPoint.fromLocalInstance(client, boat.getLocalLocation());
			return PositionRules.classify(true, boatTile, null, topLevel.isInstance());
		}
		return PositionRules.classify(false, null, WorldPoint.fromLocalInstance(client, player.getLocalLocation()),
			topLevel.isInstance());
	}

	private static int wildernessLevel(Position position)
	{
		return position.getKind() == Position.Kind.POH ? 0 : PositionRules.wildernessLevel(position.getTile());
	}

	private Map<Quest, QuestState> readQuests()
	{
		Map<Quest, QuestState> states = new EnumMap<>(Quest.class);
		for (Quest quest : Quest.values())
		{
			states.put(quest, quest.getState(client));
		}
		return states;
	}

	private PlayerSnapshot build(Position now)
	{
		Map<Integer, Integer> inventory = contents(InventoryID.INV);
		Map<Integer, Integer> worn = contents(InventoryID.WORN);
		boolean pouchHeld = inventory.keySet().stream().anyMatch(RUNE_POUCHES::contains);
		Map<Integer, Integer> varbits = new HashMap<>();
		watchedVarbits.forEach(id -> varbits.put(id, client.getVarbitValue(id)));
		Map<Integer, Integer> varps = new HashMap<>();
		watchedVarps.forEach(id -> varps.put(id, client.getVarpValue(id)));
		Map<Skill, Integer> boosted = new EnumMap<>(Skill.class);
		Map<Skill, Integer> real = new EnumMap<>(Skill.class);
		for (Skill skill : Skill.values())
		{
			boosted.put(skill, client.getBoostedSkillLevel(skill));
			real.put(skill, client.getRealSkillLevel(skill));
		}
		Set<Integer> held = new HashSet<>(inventory.keySet());
		held.addAll(worn.keySet());
		Set<WorldType> worldTypes = client.getWorldType();
		return PlayerSnapshot.builder()
			.version(++version)
			.tick(client.getTickCount())
			.position(now)
			.wildernessLevel(wildernessLevel(now))
			.membersWorld(worldTypes.contains(WorldType.MEMBERS))
			.pvpWorld(WorldType.isPvpWorld(worldTypes))
			.dormantReason(dormantReason(worldTypes))
			.accountType(client.getVarbitValue(VarbitID.IRONMAN))
			.inventory(inventory)
			.worn(worn)
			.weapon(weapon())
			.pouchHeld(pouchHeld)
			.pouch(pouchHeld ? pouch() : Map.of())
			.boostedLevels(boosted)
			.realLevels(real)
			.totalLevel(client.getTotalLevel())
			.combatLevel(client.getLocalPlayer().getCombatLevel())
			.questPoints(client.getVarpValue(VarPlayerID.QP))
			.questsRead(questsRead)
			.quests(quests)
			.varbits(varbits)
			.varps(varps)
			.unknownVars(PlayerSnapshot.UnknownVars.UNREAD)
			.spellbook(spellbook())
			.charges(chargeReader.read(held))
			.build();
	}

	private Map<Integer, Integer> contents(int containerId)
	{
		Map<Integer, Integer> contents = new HashMap<>();
		ItemContainer container = client.getItemContainer(containerId);
		if (container != null)
		{
			for (Item item : container.getItems())
			{
				if (item.getId() > 0 && item.getQuantity() > 0)
				{
					contents.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}
		return contents;
	}

	private int weapon()
	{
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		Item item = worn == null ? null : worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		return item == null ? -1 : item.getId();
	}

	private Map<Integer, Integer> pouch()
	{
		Map<Integer, Integer> runes = new HashMap<>();
		for (int i = 0; i < POUCH_TYPES.length; i++)
		{
			int rune = runeModel.pouchRune(client.getVarbitValue(POUCH_TYPES[i]));
			int quantity = client.getVarbitValue(POUCH_QUANTITIES[i]);
			if (rune > 0 && quantity > 0)
			{
				runes.merge(rune, quantity, Integer::sum);
			}
		}
		return runes;
	}

	private SpellbookState spellbook()
	{
		return new SpellbookState(
			Book.of(client.getVarbitValue(VarbitID.SPELLBOOK)),
			client.getVarbitValue(VarbitID.LUNAR_SPELLBOOK_CHANGE) == 1,
			Math.max(0, SpellbookState.CAPE_CHANGES_PER_DAY - client.getVarbitValue(VarbitID.SB_SWAP_CHECK)),
			client.getVarbitValue(VarbitID.ARCEUUS_SPELLBOOK_UNLOCKED) == 1,
			quests.get(Quest.DESERT_TREASURE_I) == QuestState.FINISHED,
			quests.get(Quest.LUNAR_DIPLOMACY) == QuestState.FINISHED,
			quests.get(Quest.DREAM_MENTOR) == QuestState.FINISHED);
	}

	private static String dormantReason(Set<WorldType> worldTypes)
	{
		for (WorldType type : worldTypes)
		{
			if (DORMANT_WORLDS.contains(type))
			{
				return type.name().toLowerCase().replace('_', ' ') + " world";
			}
		}
		return null;
	}

	private <T> T timed(String what, Supplier<T> read)
	{
		long started = System.nanoTime();
		T value = read.get();
		log.debug("read {} in {} ms", what, (System.nanoTime() - started) / 1_000_000.0);
		stats.recordRead(what, System.nanoTime() - started);
		return value;
	}
}
