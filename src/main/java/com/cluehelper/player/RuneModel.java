package com.cluehelper.player;

import java.util.*;
import java.util.stream.Collectors;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.gameval.ItemID;

/**
 * Everything except the tomes comes from the cache, so a game update that adds a staff or a rune carries
 * over by itself. The spellbook widget is never read: it is stale while the magic tab is hidden.
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuneModel
{
	private static final int PARAM_RUNE_1 = 365;
	private static final int PARAM_RUNE_COUNT_1 = 366;
	private static final int PARAM_RUNE_2 = 367;
	private static final int PARAM_RUNE_COUNT_2 = 368;
	private static final int PARAM_RUNE_3 = 369;
	private static final int PARAM_RUNE_COUNT_3 = 370;
	private static final int[][] RUNE_PARAMS = {
		{PARAM_RUNE_1, PARAM_RUNE_COUNT_1}, {PARAM_RUNE_2, PARAM_RUNE_COUNT_2}, {PARAM_RUNE_3, PARAM_RUNE_COUNT_3}};
	private static final int PARAM_NAME = 601;
	private static final int PARAM_MEMBERS = 603;
	private static final int PARAM_LEVEL = 604;
	private static final int PARAM_QUEST = 1189;
	private static final int ABSENT = -1;

	/** {element rune, enum of its staves (OBJ → BOOLEAN), enum of the pouch types that hold it}. */
	private static final int[][] ELEMENTS = {
		{ItemID.AIRRUNE, 988, 983},
		{ItemID.WATERRUNE, 989, 987},
		{ItemID.EARTHRUNE, 996, 993},
		{ItemID.FIRERUNE, 997, 994},
	};
	/** Not in the cache as data. Only the charged ids: an empty tome supplies nothing. */
	private static final int[][] TOMES = {
		{ItemID.TOME_OF_FIRE, ItemID.FIRERUNE},
		{ItemID.TOME_OF_WATER, ItemID.WATERRUNE},
		{ItemID.TOME_OF_EARTH, ItemID.EARTHRUNE},
	};

	@Getter
	private final List<Spell> spells;
	private final Map<Integer, Integer> pouchRunes;
	/** The rune itself comes first. */
	private final Map<Integer, List<Integer>> payersByRune;
	private final Map<Integer, Set<Integer>> unlimitingWornItemsByRune;
	private final Map<Integer, String> runeNames;

	/** On the client thread when {@code cache} is the client. */
	public static RuneModel load(CacheView cache)
	{
		Map<Integer, Integer> pouchRunes = enumMap(cache, EnumID.RUNEPOUCH_RUNE);
		Map<Integer, Set<Integer>> payerSets = new HashMap<>();
		Map<Integer, String> names = new HashMap<>();
		for (int rune : pouchRunes.values())
		{
			payerSets.computeIfAbsent(rune, r -> new LinkedHashSet<>()).add(rune);
			names.put(rune, cache.itemName(rune));
		}
		Map<Integer, Set<Integer>> unlimited = new HashMap<>();
		for (int[] element : ELEMENTS)
		{
			int rune = element[0];
			unlimited.computeIfAbsent(rune, r -> new TreeSet<>()).addAll(keysOf(cache, element[1]));
			for (int type : keysOf(cache, element[2]))
			{
				Integer combination = pouchRunes.get(type);
				if (combination != null)
				{
					payerSets.computeIfAbsent(rune, r -> new LinkedHashSet<>()).add(combination);
				}
			}
		}
		for (int[] tome : TOMES)
		{
			unlimited.computeIfAbsent(tome[1], r -> new TreeSet<>()).add(tome[0]);
		}
		for (int[] row : cache.multirunes())
		{
			payerSets.computeIfAbsent(row[0], r -> new LinkedHashSet<>(Collections.singleton(row[0]))).add(row[1]);
		}
		Map<Integer, List<Integer>> payers = new HashMap<>();
		payerSets.forEach((rune, set) -> payers.put(rune, List.copyOf(set)));
		Map<Integer, Set<Integer>> unlimitedCopy = new HashMap<>();
		unlimited.forEach((rune, set) -> unlimitedCopy.put(rune, Set.copyOf(set)));
		return new RuneModel(List.copyOf(spells(cache)), Map.copyOf(pouchRunes), Map.copyOf(payers),
			Map.copyOf(unlimitedCopy), Map.copyOf(names));
	}

	/** Every item that pays for a rune or stands in for one: runes, combination runes, staves and tomes. */
	public Set<Integer> sourceItemIds()
	{
		Set<Integer> ids = new HashSet<>();
		payersByRune.values().forEach(ids::addAll);
		unlimitingWornItemsByRune.values().forEach(ids::addAll);
		return Set.copyOf(ids);
	}

	/** The staves that stand in for runes, all wielded in the one weapon slot; the tomes go in the shield slot. */
	public Set<Integer> staves()
	{
		Set<Integer> tomes = Arrays.stream(TOMES).map(tome -> tome[0]).collect(Collectors.toSet());
		return unlimitingWornItemsByRune.values().stream().flatMap(Set::stream).filter(id -> !tomes.contains(id))
			.collect(Collectors.toUnmodifiableSet());
	}

	/** -1 for an unknown pouch type. */
	public int pouchRune(int pouchType)
	{
		return pouchRunes.getOrDefault(pouchType, -1);
	}

	public boolean isRune(int itemId)
	{
		return payersByRune.containsKey(itemId);
	}

	private String runeName(int rune)
	{
		return runeNames.getOrDefault(rune, "rune " + rune);
	}

	public Supply supply(int rune, int quantity, PlayerSnapshot player)
	{
		for (int source : unlimitingWornItemsByRune.getOrDefault(rune, Collections.emptySet()))
		{
			if (player.count(Container.WORN, source) > 0)
			{
				return new Supply(true, Integer.MAX_VALUE, List.of(new ItemUse(Container.WORN, source, 1)));
			}
		}
		List<ItemUse> used = new ArrayList<>();
		int have = 0;
		for (Container container : new Container[]{Container.INVENTORY, Container.RUNE_POUCH, Container.BANK, Container.GROUP_STORAGE})
		{
			for (int payer : payersByRune.getOrDefault(rune, List.of(rune)))
			{
				int count = player.count(container, payer);
				if (count > 0 && have < quantity)
				{
					used.add(new ItemUse(container, payer, Math.min(count, quantity - have)));
				}
				have += count;
			}
		}
		return new Supply(have >= quantity, have, have >= quantity ? List.copyOf(used) : List.of());
	}

	public String shortfall(int rune, int quantity, int have)
	{
		return "needs " + quantity + " " + runeName(rune).toLowerCase() + (quantity == 1 ? "" : "s") + " (has " + have + ")";
	}

	@Value
	public static class Supply
	{
		boolean enough;
		/** {@link Integer#MAX_VALUE} for a staff or tome. */
		int have;
		/** Empty unless enough. */
		List<ItemUse> witness;
	}

	private static List<Spell> spells(CacheView cache)
	{
		Map<Integer, Quest> questsById = new HashMap<>();
		for (Quest quest : Quest.values())
		{
			questsById.put(quest.getId(), quest);
		}
		List<Spell> spells = new ArrayList<>();
		int[] books = cache.enumKeys(EnumID.SPELLBOOKS);
		int[] bookEnums = cache.enumIntValues(EnumID.SPELLBOOKS);
		for (int b = 0; b < books.length; b++)
		{
			Book book = Book.of(books[b]);
			if (book == null)
			{
				continue;
			}
			for (int obj : cache.enumIntValues(bookEnums[b]))
			{
				spells.add(spell(cache, book, obj, questsById));
			}
		}
		return spells;
	}

	private static Spell spell(CacheView cache, Book book, int obj, Map<Integer, Quest> questsById)
	{
		List<Spell.RuneCost> runes = new ArrayList<>();
		for (int[] param : RUNE_PARAMS)
		{
			int rune = cache.itemIntParam(obj, param[0], ABSENT);
			if (rune > 0)
			{
				runes.add(new Spell.RuneCost(rune, cache.itemIntParam(obj, param[1], 0)));
			}
		}
		String name = cache.itemStringParam(obj, PARAM_NAME);
		return new Spell(book, name == null ? "spell " + obj : name, obj,
			Math.max(0, cache.itemIntParam(obj, PARAM_LEVEL, 0)), cache.itemIntParam(obj, PARAM_MEMBERS, 0) == 1,
			questsById.get(cache.itemIntParam(obj, PARAM_QUEST, ABSENT)), List.copyOf(runes));
	}

	private static Map<Integer, Integer> enumMap(CacheView cache, int enumId)
	{
		int[] keys = cache.enumKeys(enumId);
		int[] values = cache.enumIntValues(enumId);
		Map<Integer, Integer> map = new HashMap<>();
		for (int i = 0; i < keys.length; i++)
		{
			map.put(keys[i], values[i]);
		}
		return map;
	}

	private static List<Integer> keysOf(CacheView cache, int enumId)
	{
		List<Integer> keys = new ArrayList<>();
		for (int key : cache.enumKeys(enumId))
		{
			keys.add(key);
		}
		return keys;
	}
}
