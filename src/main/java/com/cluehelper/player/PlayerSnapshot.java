package com.cluehelper.player;

import java.util.*;
import java.util.function.IntFunction;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

/**
 * Immutable, so any thread can read it: its maps and sets are handed over fresh and never changed. Walking alone doesn't
 * make a new version: see {@link PlayerModel#getPosition()}.
 */
@Value
@Builder(toBuilder = true)
public class PlayerSnapshot
{
	/** How a var the snapshot didn't read is judged; only test loadouts declare PASS or FAIL. */
	public enum UnknownVars
	{
		UNREAD, PASS, FAIL
	}

	long version;
	@Builder.Default
	Position position = Position.NONE;
	int wildernessLevel;
	boolean membersWorld;
	/** Null on a normal world. */
	String dormantReason;
	AccountType accountType;
	@Builder.Default
	Map<Integer, Integer> inventory = Map.of();
	@Builder.Default
	Map<Integer, Integer> worn = Map.of();
	/** Empty unless a rune pouch is held: its varbits keep their values while it is banked. */
	@Builder.Default
	Map<Integer, Integer> pouch = Map.of();
	@Builder.Default
	Map<Skill, Integer> boostedLevels = Map.of();
	@Builder.Default
	Map<Skill, Integer> realLevels = Map.of();
	int totalLevel;
	int combatLevel;
	/** Tracks the Music cape still needs; 0 in a snapshot that never counted them. */
	int lockedMusicTracks;
	/** Quest points the player lacks of all the game offers; 0 in a snapshot that never counted them. */
	int missingQuestPoints;
	boolean questsRead;
	@Builder.Default
	Map<Quest, QuestState> quests = Map.of();
	@Builder.Default
	Map<Integer, Integer> varbits = Map.of();
	@Builder.Default
	Map<Integer, Integer> varps = Map.of();
	@Builder.Default
	UnknownVars unknownVars = UnknownVars.UNREAD;
	SpellbookState spellbook;
	/** Held items only, except in a banked snapshot. */
	@Builder.Default
	Map<Integer, Charge> charges = Map.of();
	int freeInventorySlots;
	/** Placeholders left out; empty except in a banked snapshot. */
	@Builder.Default
	Map<Integer, Integer> bank = Map.of();
	/** Empty except in a banked snapshot. */
	@Builder.Default
	Map<Integer, Integer> groupStorage = Map.of();
	@Builder.Default
	HouseState house = HouseState.NONE;
	/** Patch names known to be grown: spirit trees, and the grown patches read from Time Tracking. */
	@Builder.Default
	Set<String> plantedTrees = Set.of();
	/** A planting task's places where every allotment has a crop still growing. */
	@Builder.Default
	Set<WorldPoint> growingAllotments = Set.of();
	/** Account unlocks seen in play, named as the catalog's {@code UNLOCK_} tokens name them (without the prefix). */
	@Builder.Default
	Set<String> unlocks = Set.of();
	/** The three-letter code where a ring's Last-destination goes; null when unknown. */
	String lastFairyRing;

	public int boosted(Skill skill)
	{
		return boostedLevels.getOrDefault(skill, 1);
	}

	public int real(Skill skill)
	{
		return realLevels.getOrDefault(skill, 1);
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
}
