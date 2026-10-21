package com.cluehelper.player;

import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/**
 * Immutable, so any thread can read it. Walking alone doesn't make a new version: see
 * {@link PlayerModel#getPosition()}.
 */
@Getter
public final class PlayerSnapshot
{
	/** How a var the snapshot didn't read is judged; only test loadouts declare PASS or FAIL. */
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
	/** Null on a normal world. */
	private final String dormantReason;
	/** Varbit 1777: 0 normal, 1 Ironman, 2 Ultimate, 3 Hardcore, 4 Group, 5 Hardcore Group, 6 Unranked Group. */
	private final int accountType;
	private final Map<Integer, Integer> inventory;
	private final Map<Integer, Integer> worn;
	/** -1 when nothing is wielded. */
	private final int weapon;
	/** The pouch varbits keep their values while it is banked, so they only count while it is held. */
	private final boolean pouchHeld;
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
	/** Held items only, except in a banked snapshot. */
	private final Map<Integer, Charge> charges;
	private final int freeInventorySlots;
	/** Placeholders left out; empty except in a banked snapshot. */
	private final Map<Integer, Integer> bank;
	/** Empty except in a banked snapshot. */
	private final Map<Integer, Integer> groupStorage;
	private final HouseState house;
	/** Patch names of spirit trees known to be grown. */
	private final Set<String> plantedTrees;
	/** A code like "CKS", where a ring's Last-destination goes; null when unknown. */
	private final String lastFairyRing;

	@Builder(toBuilder = true)
	private PlayerSnapshot(long version, int tick, Position position, int wildernessLevel, boolean membersWorld,
		boolean pvpWorld, String dormantReason, int accountType, Map<Integer, Integer> inventory,
		Map<Integer, Integer> worn, int weapon, boolean pouchHeld, Map<Integer, Integer> pouch,
		Map<Skill, Integer> boostedLevels, Map<Skill, Integer> realLevels, int totalLevel, int combatLevel,
		int questPoints, boolean questsRead, Map<Quest, QuestState> quests, Map<Integer, Integer> varbits,
		Map<Integer, Integer> varps, UnknownVars unknownVars, SpellbookState spellbook, Map<Integer, Charge> charges,
		int freeInventorySlots, Map<Integer, Integer> bank, Map<Integer, Integer> groupStorage, HouseState house,
		Set<String> plantedTrees, String lastFairyRing)
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
		this.lastFairyRing = lastFairyRing;
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
