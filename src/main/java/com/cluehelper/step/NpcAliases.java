package com.cluehelper.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Core names the NPC who stands there after a quest; before it, another NPC does the same job. */
final class NpcAliases
{
	/** Laidee Gnonock takes over Ghommal's door after While Guthix Sleeps. */
	private static final Map<String, String> BEFORE_QUEST = Map.of("Laidee Gnonock", "Ghommal");

	private NpcAliases()
	{
	}

	static List<String> withAliases(List<String> names)
	{
		List<String> all = new ArrayList<>(names);
		names.stream().map(BEFORE_QUEST::get).filter(alias -> alias != null && !all.contains(alias)).forEach(all::add);
		return List.copyOf(all);
	}

	static String either(String name)
	{
		String alias = BEFORE_QUEST.get(name);
		return alias == null ? name : name + " or " + alias;
	}
}
