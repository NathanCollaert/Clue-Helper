package com.cluehelper.player;

import com.cluehelper.Tables;
import com.cluehelper.memory.BankMemory;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Event handlers run on the client thread and only mark the model dirty; {@link #onGameTick()} rebuilds the snapshot
 * at most once per tick. Off the client thread, read snapshots only.
 */
@Slf4j
@Singleton
public class PlayerModel
{
	private static final Set<Integer> RUNE_POUCHES = Tables.idSet("rune pouches");
	private static final int[] POUCH_TYPES = Tables.rows("rune pouch slots").stream().mapToInt(row -> row.id(0)).toArray();
	private static final int[] POUCH_QUANTITIES = Tables.rows("rune pouch slots").stream().mapToInt(row -> row.id(1)).toArray();
	private static final List<Integer> OWN_VARPS = List.of(VarPlayerID.QP, VarPlayerID.AIDE_TELE_TIMER,
		VarPlayerID.SLUG2_REGIONUID);
	/** Last Man Standing worlds aren't here: outside the arena they play as ordinary worlds. */
	private static final Set<WorldType> DORMANT_WORLDS = EnumSet.of(WorldType.SEASONAL, WorldType.DEADMAN,
		WorldType.BETA_WORLD, WorldType.TOURNAMENT_WORLD, WorldType.QUEST_SPEEDRUNNING, WorldType.PVP_ARENA,
		WorldType.NOSAVE_MODE);

	public static final int UNKNOWN_TICK = -1;
	private static final int INVENTORY_SLOTS = 28;

	@Inject
	private Client client;
	@Inject
	private BankMemory bankMemory;
	@Inject
	private ChargeReader chargeReader;
	@Inject
	private CheckLines checkLines;
	private final List<Consumer<PlayerSnapshot>> listeners = new CopyOnWriteArrayList<>();
	private final Queue<Integer> pendingVarbits = new ConcurrentLinkedQueue<>();
	private final Queue<Integer> pendingVarps = new ConcurrentLinkedQueue<>();

	private final Set<Integer> watchedVarbits = new HashSet<>();
	private final Set<Integer> watchedVarps = new HashSet<>();
	private final Set<Integer> watchedVarpIndices = new HashSet<>();
	private Map<Quest, QuestState> quests = Map.of();
	private boolean questsRead;
	private volatile boolean questsDirty;
	private volatile boolean dirty;
	private HouseState house = HouseState.NONE;
	private Set<String> plantedTrees = Set.of();
	private Set<String> unlocks = Set.of();
	/** Items owned but known not to do what their id promises: a steel key ring the Metal key was taken off. */
	private Set<Integer> uncounted = Set.of();
	/** Grown patches other than spirit trees, named as the step data names them. */
	private Set<String> grownPatches = Set.of();
	private String lastFairyRing;
	private String dirtyBecause;
	private long version;
	private Map<Integer, Integer> fakeCarried = Map.of();

	/** Null until the first tick after login. */
	@Getter
	private volatile RuneModel runeModel;
	/** Null while logged out. */
	@Getter
	private volatile PlayerSnapshot snapshot;
	/**
	 * Read every tick. A snapshot copies it only when its kind or Wilderness level changes, so walking doesn't make a
	 * new snapshot version.
	 */
	@Getter
	private volatile Position position = Position.NONE;
	/**
	 * The tick a pending Spellbook Swap began; {@link #UNKNOWN_TICK} when none is pending, or it was already pending
	 * when first seen.
	 */
	@Getter
	private volatile int pendingRevertSinceTick = UNKNOWN_TICK;

	public void start()
	{
		List<Integer> varbits = new ArrayList<>(Tables.idSet("player varbits"));
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

	public Runnable addListener(Consumer<PlayerSnapshot> listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** Any thread; the vars are watched from the next tick on. */
	public void watch(Collection<Integer> varbits, Collection<Integer> varps)
	{
		pendingVarbits.addAll(varbits);
		pendingVarps.addAll(varps);
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
		if (event.getContainerId() == InventoryID.INV || event.getContainerId() == InventoryID.WORN
			|| event.getContainerId() == InventoryID.HUNTSMANS_KIT)
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

	/** Client thread. */
	public void setPlantedTrees(Set<String> next)
	{
		plantedTrees = Set.copyOf(next);
		markDirty("spirit trees");
	}

	/** Client thread. */
	public void setUnlocks(Set<String> next)
	{
		unlocks = Set.copyOf(next);
		markDirty("unlocks");
	}

	/** Client thread. */
	public void setUncounted(Set<Integer> next)
	{
		uncounted = Set.copyOf(next);
		markDirty("uncounted items");
	}

	/** Whether owning {@code itemId} counts for what it would bring: false for an uncounted one. */
	public boolean counts(int itemId)
	{
		return !uncounted.contains(itemId);
	}

	/** {@code items} without the uncounted ones. */
	public Map<Integer, Integer> counted(Map<Integer, Integer> items)
	{
		Map<Integer, Integer> counted = new HashMap<>(items);
		counted.keySet().removeAll(uncounted);
		return counted;
	}

	/** Client thread. */
	public void setGrownPatches(Set<String> next)
	{
		grownPatches = Set.copyOf(next);
		markDirty("grown patches");
	}

	/** Client thread. */
	public void setLastFairyRing(String code)
	{
		lastFairyRing = code;
		markDirty("fairy ring");
	}

	/** Client thread. */
	public void setHouse(HouseState next)
	{
		house = next;
		markDirty("house");
	}

	public void onChargesLearned()
	{
		markDirty("charges");
	}

	/** Client thread. Also answers for items that aren't held, such as the remembered bank's. */
	public Map<Integer, Charge> charges(Collection<Integer> itemIds)
	{
		chargeReader.rememberVarbitCharges(itemIds);
		Map<Integer, Charge> charges = new HashMap<>(chargeReader.read(itemIds));
		for (int id : itemIds)
		{
			CheckLines.Checked checked = checkLines.charges().get(id);
			if (checked != null)
			{
				charges.putIfAbsent(id, new Charge(checked.charges, Charge.Source.CHECK_LINE));
			}
		}
		return charges;
	}

	public void onGameTick()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}
		if (runeModel == null)
		{
			runeModel = RuneModel.load(new ClientCacheView(client));
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
			quests = readQuests();
			questsRead = true;
			markDirty("quests");
		}
		if (dirty)
		{
			dirty = false;
			PlayerSnapshot built = build(now);
			trackPendingRevert(last, built);
			log.debug("snapshot rebuilt ({})", dirtyBecause);
			snapshot = built;
			listeners.forEach(listener -> listener.accept(built));
		}
	}

	private void trackPendingRevert(PlayerSnapshot last, PlayerSnapshot built)
	{
		boolean pending = built.getSpellbook() != null && built.getSpellbook().isPendingRevert();
		boolean wasPending = last != null && last.getSpellbook() != null && last.getSpellbook().isPendingRevert();
		if (!pending)
		{
			pendingRevertSinceTick = UNKNOWN_TICK;
		}
		else if (!wasPending)
		{
			pendingRevertSinceTick = last == null ? UNKNOWN_TICK : client.getTickCount();
		}
	}

	/** Client thread. Dev builds only: items to see in the inventory on top of the real ones; empty for none. */
	public void fakeCarried(Map<Integer, Integer> items)
	{
		fakeCarried = Map.copyOf(items);
		markDirty("fake carried items");
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
		Integer id;
		boolean added = false;
		while ((id = pendingVarbits.poll()) != null)
		{
			if (watchedVarbits.add(id))
			{
				VarbitComposition varbit = client.getVarbit(id);
				if (varbit != null)
				{
					watchedVarpIndices.add(varbit.getIndex());
				}
				added = true;
			}
		}
		while ((id = pendingVarps.poll()) != null)
		{
			if (watchedVarps.add(id))
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
			return PositionRules.onBoat(boatTile);
		}
		return PositionRules.onFoot(WorldPoint.fromLocalInstance(client, player.getLocalLocation()), topLevel.isInstance());
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
		Map<Integer, Integer> inventory = counted(contents(InventoryID.INV));
		fakeCarried.forEach((id, quantity) -> inventory.merge(id, quantity, Integer::sum));
		ItemContainer inventoryContainer = client.getItemContainer(InventoryID.INV);
		int usedSlots = inventoryContainer == null ? 0 : (int) Arrays.stream(inventoryContainer.getItems())
			.filter(item -> item.getId() > 0 && item.getQuantity() > 0).count();
		Map<Integer, Integer> worn = counted(contents(InventoryID.WORN));
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
		Set<String> grown = new HashSet<>(plantedTrees);
		grown.addAll(grownPatches);
		return PlayerSnapshot.builder()
			.version(++version)
			.tick(client.getTickCount())
			.position(now)
			.wildernessLevel(wildernessLevel(now))
			.membersWorld(worldTypes.contains(WorldType.MEMBERS))
			.pvpWorld(WorldType.isPvpWorld(worldTypes))
			.dormantReason(dormantReason(worldTypes))
			.accountType(AccountType.of(client.getVarbitValue(VarbitID.IRONMAN)))
			.inventory(bankMemory.withKit(inventory))
			.worn(worn)
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
			.charges(charges(held))
			.freeInventorySlots(Math.max(0, INVENTORY_SLOTS - usedSlots))
			.house(house)
			.plantedTrees(grown)
			.unlocks(unlocks)
			.lastFairyRing(lastFairyRing)
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
		return worldTypes.stream().filter(DORMANT_WORLDS::contains).findFirst()
			.map(type -> type.name().toLowerCase().replace('_', ' ') + " world").orElse(null);
	}

	/** Client thread. Whether an inventory item's name starts with {@code nameStart}, lower case. */
	public boolean carriesItemNamed(String nameStart)
	{
		PlayerSnapshot snapshot = getSnapshot();
		return snapshot != null && snapshot.carriesItemNamed(nameStart, id -> client.getItemDefinition(id).getName());
	}

	/** Client thread. Held or worn: an item put away (in a deposit box) is gone from both. */
	public boolean holdsOrWearsItemNamed(String nameStart)
	{
		PlayerSnapshot snapshot = getSnapshot();
		return snapshot != null && snapshot.holdsOrWearsItemNamed(nameStart, id -> client.getItemDefinition(id).getName());
	}

	/** Inventory and worn; empty while logged out. */
	public Set<Integer> carriedIds()
	{
		PlayerSnapshot snapshot = getSnapshot();
		return snapshot == null ? Set.of() : snapshot.carriedIds();
	}

	public Set<Integer> inventoryIds()
	{
		PlayerSnapshot snapshot = getSnapshot();
		return snapshot == null ? Set.of() : snapshot.getInventory().keySet();
	}

	public boolean finished(Quest quest)
	{
		PlayerSnapshot snapshot = getSnapshot();
		return snapshot != null && snapshot.finished(quest);
	}

	public Set<Integer> wornIds()
	{
		PlayerSnapshot snapshot = getSnapshot();
		return snapshot == null ? Set.of() : snapshot.getWorn().keySet();
	}

	/** The standard book until the spellbook is read. */
	public Book currentBook()
	{
		PlayerSnapshot snapshot = getSnapshot();
		return snapshot == null || snapshot.getSpellbook() == null ? Book.STANDARD : snapshot.getSpellbook().getBook();
	}

	/** Client thread only. */
	@RequiredArgsConstructor
	static final class ClientCacheView implements CacheView
	{
		private static final int MULTIRUNES_TABLE = 91;
		private static final int REQUIRED_RUNE_COLUMN = 0;
		private static final int ALSO_COUNTS_COLUMN = 1;

		private final Client client;

		@Override
		public int[] enumKeys(int enumId)
		{
			EnumComposition e = client.getEnum(enumId);
			return e == null ? new int[0] : e.getKeys();
		}

		@Override
		public int[] enumIntValues(int enumId)
		{
			EnumComposition e = client.getEnum(enumId);
			return e == null ? new int[0] : e.getIntVals();
		}

		@Override
		public int itemIntParam(int itemId, int param, int absent)
		{
			ItemComposition item = client.getItemDefinition(itemId);
			return hasParam(item, param) ? item.getIntValue(param) : absent;
		}

		@Override
		public String itemStringParam(int itemId, int param)
		{
			ItemComposition item = client.getItemDefinition(itemId);
			return hasParam(item, param) ? item.getStringValue(param) : null;
		}

		private static boolean hasParam(ItemComposition item, int param)
		{
			return item.getParams() != null && item.getParams().get(param) != null;
		}

		@Override
		public String itemName(int itemId)
		{
			return client.getItemDefinition(itemId).getName();
		}

		@Override
		public List<int[]> multirunes()
		{
			List<int[]> rows = new ArrayList<>();
			for (int row : client.getDBTableRows(MULTIRUNES_TABLE))
			{
				Object[] required = client.getDBTableField(row, REQUIRED_RUNE_COLUMN, 0);
				Object[] alsoCounts = client.getDBTableField(row, ALSO_COUNTS_COLUMN, 0);
				if (required.length > 0 && alsoCounts.length > 0)
				{
					rows.add(new int[]{(Integer) required[0], (Integer) alsoCounts[0]});
				}
			}
			return rows;
		}
	}
}
