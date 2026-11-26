package com.cluehelper.step;

import java.util.*;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.*;
import static java.util.stream.Collectors.*;

/** The names of core's clue objects, which the bake writes into the acts that search them. */
public final class ObjectNames
{
	/** The cache's name for an object without one: a multiloc, named only in its shown form. */
	private static final String NO_NAME = "null";
	private static final Map<String, List<String>> CORE_BY_KEY = coreNamesByKey();

	private ObjectNames()
	{
	}

	/** Core's own names for the step's objects; empty where core names none (the Al Kharid tent's boxes are only an id to it). */
	public static List<String> core(String stepKey)
	{
		return CORE_BY_KEY.getOrDefault(stepKey, List.of());
	}

	/** "Search the boxes", by the cache's name of the first of the objects it names; "Search it" where it names none. */
	public static String search(Collection<Integer> objectIds)
	{
		List<String> names = objectIds.stream().map(ObjectNames::cacheName).filter(name -> !NO_NAME.equals(name)).distinct()
			.collect(toList());
		return names.isEmpty() ? "Search it" : "Search the " + names.get(0).toLowerCase(Locale.ROOT);
	}

	/** generate.py writes a row for each of core's clue objects; one missing means the table is older than core. */
	private static String cacheName(int objectId)
	{
		String name = TargetNames.OBJECTS.get(objectId);
		if (name == null)
		{
			throw new IllegalStateException("target-names.tsv has no object " + objectId + ": run generate.py");
		}
		return name;
	}

	private static Map<String, List<String>> coreNamesByKey()
	{
		try
		{
			ClueScrollPlugin plugin = CoreClues.plugin();
			Map<String, List<String>> byKey = new HashMap<>();
			CoreClues.steps().values().forEach(clue -> byKey.put(CoreClueReader.facts(clue, plugin).getKey(), of(clue)));
			return byKey;
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("can't read core's clues", e);
		}
	}

	private static List<String> of(ClueScroll clue)
	{
		String[] names = clue instanceof NamedObjectClueScroll ? ((NamedObjectClueScroll) clue).getObjectNames() : null;
		return names == null ? List.of() : Arrays.stream(names).filter(Objects::nonNull).distinct().collect(toUnmodifiableList());
	}
}
