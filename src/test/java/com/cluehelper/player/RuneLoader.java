package com.cluehelper.player;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import net.runelite.api.EnumID;
import net.runelite.api.gameval.ItemID;
import static java.util.stream.Collectors.*;

/**
 * Builds the rune model from the cache's tables in {@code /runes/cache-runes.json}, cut from a cache dump by
 * Clue-Helper-Tools/tools/spellbook/s2_test_slice.py, for {@code DataBake} to write as {@code player/runes.json}.
 * Everything except the tomes comes from the cache, so a game update that adds a staff or a rune carries over with
 * the next bake.
 */
public final class RuneLoader
{
	public static final Path JSON = Paths.get("src/main/resources/com/cluehelper/player/runes.json");
	private static final String SLICE = "/runes/cache-runes.json";
	private static final int PARAM_NAME = 601;
	private static final int PARAM_SPELL_WIDGET = 596;

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

	private RuneLoader()
	{
	}

	public static RuneModel load()
	{
		return load(new RuneModelTest.SliceCacheView(SLICE));
	}

	/** Every spell on every book, as the cache lists them. */
	public static List<Spell> spells()
	{
		return spells(new RuneModelTest.SliceCacheView(SLICE));
	}

	public static String json(Gson gson)
	{
		return gson.toJson(load()) + "\n";
	}

	static RuneModel load(CacheView cache)
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
		Set<Integer> tomes = Arrays.stream(TOMES).map(tome -> tome[0]).collect(toSet());
		Set<Integer> staves = unlimited.values().stream().flatMap(Set::stream).filter(id -> !tomes.contains(id)).collect(toCollection(TreeSet::new));
		return new RuneModel(new TreeMap<>(pouchRunes), new TreeMap<>(payers), new TreeMap<>(unlimitedCopy), new TreeMap<>(names), staves);
	}

	static List<Spell> spells(CacheView cache)
	{
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
				spells.add(spell(cache, book, obj));
			}
		}
		return spells;
	}

	private static Spell spell(CacheView cache, Book book, int obj)
	{
		String name = cache.itemStringParam(obj, PARAM_NAME);
		int widget = cache.itemIntParam(obj, PARAM_SPELL_WIDGET, -1);
		return new Spell(book, name == null ? "spell " + obj : name, obj, widget > 0 ? widget : -1);
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
		return Arrays.stream(cache.enumKeys(enumId)).boxed().collect(toList());
	}
}
