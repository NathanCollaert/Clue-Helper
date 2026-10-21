package com.cluehelper.player;

import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/**
 * What the player has, copied on the client thread (PLAN §4 item 3, §5.2). Immutable: every map is
 * copied in, so the planner can read it from any thread. A new version is made whenever something
 * availability depends on changes; walking alone doesn't make one (see {@link PlayerModel#getPosition()}).
 */
@Getter
public final class PlayerSnapshot
{
	/** How a var the snapshot didn't read is judged: live it is unread; fixture loadouts declare pass or fail. */
	public enum UnknownVars
	{
		UNREAD, PASS, FAIL
	}

	private final long version;
	private final int tick;
	private final Position position;
	private final int wildernessLevel;
	private final boolean membersWorld;
	private final boolean pvpWorld;
	/** Why the plugin should stay quiet on this world (Leagues, Deadman, beta); null on a normal world. */
	private final String dormantReason;
	/** Varbit 1777: 0 normal, 1 Ironman, 2 Ultimate, 3 Hardcore, 4 Group, 5 Hardcore Group, 6 Unranked Group. */
	private final int accountType;
	private final Map<Integer, Integer> inventory;
	private final Map<Integer, Integer> worn;
	/** The worn weapon slot's item, or -1. */
	private final int weapon;
	/** A rune pouch is in the inventory. Its varbits stay current while it is banked, so they only count then. */
	private final boolean pouchHeld;
	/** Rune item id → quantity; empty unless {@link #pouchHeld}. */
	private final Map<Integer, Integer> pouch;
	private final Map<Skill, Integer> boostedLevels;
	private final Map<Skill, Integer> realLevels;
	private final int totalLevel;
	private final int combatLevel;
	private final int questPoints;
	private final boolean questsRead;
	private final Map<Quest, QuestState> quests;
	private final Map<Integer, Integer> varbits;
	private final Map<Integer, Integer> varps;
	private final UnknownVars unknownVars;
	private final SpellbookState spellbook;
	/** Per held item id, where its charges are known; in a banked snapshot banked items too. */
	private final Map<Integer, Charge> charges;
	/** Empty inventory slots: withdrawing more stacks than this means depositing first (PLAN §5.4 Edge cases). */
	private final int freeInventorySlots;
	/** The remembered bank, placeholders left out; empty except in a banked snapshot ({@code A_banked}, B15). */
	private final Map<Integer, Integer> bank;
	/** GIM group storage, remembered like the bank; empty except in a banked snapshot. */
	private final Map<Integer, Integer> groupStorage;
	/** The player's own house (B16); {@link HouseState#NONE} when nothing is known. */
	private final HouseState house;
	/** Planted spirit trees known grown, by patch name (B17); a patch never seen is not in it. */
	private final Set<String> plantedTrees;

	@Builder(toBuilder = true)
	private PlayerSnapshot(long version, int tick, Position position, int wildernessLevel, boolean membersWorld,
		boolean pvpWorld, String dormantReason, int accountType, Map<Integer, Integer> inventory,
		Map<Integer, Integer> worn, int weapon, boolean pouchHeld, Map<Integer, Integer> pouch,
		Map<Skill, Integer> boostedLevels, Map<Skill, Integer> realLevels, int totalLevel, int combatLevel,
		int questPoints, boolean questsRead, Map<Quest, QuestState> quests, Map<Integer, Integer> varbits,
		Map<Integer, Integer> varps, UnknownVars unknownVars, SpellbookState spellbook, Map<Integer, Charge> charges,
		int freeInventorySlots, Map<Integer, Integer> bank, Map<Integer, Integer> groupStorage, HouseState house,
		Set<String> plantedTrees)
	{
		this.version = version;
		this.tick = tick;
		this.position = position == null ? Position.NONE : position;
		this.wildernessLevel = wildernessLevel;
		this.membersWorld = membersWorld;
		this.pvpWorld = pvpWorld;
		this.dormantReason = dormantReason;
		this.accountType = accountType;
		this.inventory = copy(inventory);
		this.worn = copy(worn);
		this.weapon = weapon;
		this.pouchHeld = pouchHeld;
		this.pouch = pouchHeld ? copy(pouch) : Map.of();
		this.boostedLevels = copy(boostedLevels);
		this.realLevels = copy(realLevels);
		this.totalLevel = totalLevel;
		this.combatLevel = combatLevel;
		this.questPoints = questPoints;
		this.questsRead = questsRead;
		this.quests = copy(quests);
		this.varbits = copy(varbits);
		this.varps = copy(varps);
		this.unknownVars = unknownVars == null ? UnknownVars.UNREAD : unknownVars;
		this.spellbook = spellbook;
		this.charges = copy(charges);
		this.freeInventorySlots = freeInventorySlots;
		this.bank = copy(bank);
		this.groupStorage = copy(groupStorage);
		this.house = house == null ? HouseState.NONE : house;
		this.plantedTrees = plantedTrees == null ? Set.of() : Set.copyOf(plantedTrees);
	}

	public int boosted(Skill skill)
	{
		return boostedLevels.getOrDefault(skill, 1);
	}

	public int count(Container container, int itemId)
	{
		if (container == Container.INVENTORY)
		{
			return inventory.getOrDefault(itemId, 0);
		}
		if (container == Container.WORN)
		{
			return worn.getOrDefault(itemId, 0);
		}
		if (container == Container.BANK)
		{
			return bank.getOrDefault(itemId, 0);
		}
		if (container == Container.GROUP_STORAGE)
		{
			return groupStorage.getOrDefault(itemId, 0);
		}
		return container == Container.RUNE_POUCH ? pouch.getOrDefault(itemId, 0) : 0;
	}

	public boolean finished(Quest quest)
	{
		return quests.get(quest) == QuestState.FINISHED;
	}

	/** Null when the snapshot didn't read it. */
	public Integer varbit(int id)
	{
		return varbits.get(id);
	}

	/** Null when the snapshot didn't read it. */
	public Integer varp(int id)
	{
		return varps.get(id);
	}

	private static <K, V> Map<K, V> copy(Map<K, V> map)
	{
		return map == null ? Map.of() : Map.copyOf(map);
	}
}
