package com.cluehelper.player;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import lombok.Builder;
import lombok.Getter;
import net.runelite.api.Item;
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
	private final AccountType accountType;
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
	/** Patch names known to be grown: spirit trees, and the grown patches read from Time Tracking (a Farming Guild redwood). */
	private final Set<String> plantedTrees;
	/** Account unlocks seen in play, named as the catalog's {@code UNLOCK_} tokens name them (without the prefix). */
	private final Set<String> unlocks;
	/** A code like "CKS", where a ring's Last-destination goes; null when unknown. */
	private final String lastFairyRing;

	@Builder(toBuilder = true)
	private PlayerSnapshot(long version, int tick, Position position, int wildernessLevel, boolean membersWorld,
		boolean pvpWorld, String dormantReason, AccountType accountType, Map<Integer, Integer> inventory,
		Map<Integer, Integer> worn, int weapon, boolean pouchHeld, Map<Integer, Integer> pouch,
		Map<Skill, Integer> boostedLevels, Map<Skill, Integer> realLevels, int totalLevel, int combatLevel,
		int questPoints, boolean questsRead, Map<Quest, QuestState> quests, Map<Integer, Integer> varbits,
		Map<Integer, Integer> varps, UnknownVars unknownVars, SpellbookState spellbook, Map<Integer, Charge> charges,
		int freeInventorySlots, Map<Integer, Integer> bank, Map<Integer, Integer> groupStorage, HouseState house,
		Set<String> plantedTrees, Set<String> unlocks, String lastFairyRing)
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
		this.unlocks = unlocks == null ? Set.of() : Set.copyOf(unlocks);
		this.lastFairyRing = lastFairyRing;
	}

	public int boosted(Skill skill)
	{
		return boostedLevels.getOrDefault(skill, 1);
	}

	/** Whether an inventory item's name starts with {@code nameStart}, lower case. */
	public boolean carriesItemNamed(String nameStart, IntFunction<String> names)
	{
		return inventory.keySet().stream().anyMatch(id -> names.apply(id).toLowerCase().startsWith(nameStart));
	}

	/** Whether an item in the inventory or worn has a name starting with {@code nameStart}, lower case. */
	public boolean holdsOrWearsItemNamed(String nameStart, IntFunction<String> names)
	{
		return carriedIds().stream().anyMatch(id -> names.apply(id).toLowerCase().startsWith(nameStart));
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

	/** The item's count in the inventory and worn together. */
	public int carried(int itemId)
	{
		return inventory.getOrDefault(itemId, 0) + worn.getOrDefault(itemId, 0);
	}

	/** The item's count in the bank and the group storage together; 0 outside a banked snapshot. */
	public int stored(int itemId)
	{
		return bank.getOrDefault(itemId, 0) + groupStorage.getOrDefault(itemId, 0);
	}

	public Set<Integer> carriedIds()
	{
		Set<Integer> ids = new HashSet<>(inventory.keySet());
		ids.addAll(worn.keySet());
		return Collections.unmodifiableSet(ids);
	}

	public Set<Integer> storedIds()
	{
		Set<Integer> ids = new HashSet<>(bank.keySet());
		ids.addAll(groupStorage.keySet());
		return Collections.unmodifiableSet(ids);
	}

	/** Inventory and worn items by id, their counts summed. */
	public Map<Integer, Integer> carriedItems()
	{
		return summed(inventory, worn);
	}

	/** Bank and group storage items by id, their counts summed; empty outside a banked snapshot. */
	public Map<Integer, Integer> storedItems()
	{
		return summed(bank, groupStorage);
	}

	private static Map<Integer, Integer> summed(Map<Integer, Integer> first, Map<Integer, Integer> second)
	{
		Map<Integer, Integer> items = new LinkedHashMap<>(first);
		second.forEach((id, quantity) -> items.merge(id, quantity, Integer::sum));
		return Collections.unmodifiableMap(items);
	}

	/** Item counts as core's requirements test them. */
	public static Item[] items(Map<Integer, Integer> counts)
	{
		return counts.entrySet().stream().map(e -> new Item(e.getKey(), e.getValue())).toArray(Item[]::new);
	}

	public static Item[] oneOfEach(Set<Integer> itemIds)
	{
		return itemIds.stream().map(id -> new Item(id, 1)).toArray(Item[]::new);
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
