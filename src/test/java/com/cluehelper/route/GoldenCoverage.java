package com.cluehelper.route;

import com.cluehelper.player.Container;
import com.cluehelper.player.PositionRules;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.Category;
import com.cluehelper.transport.SpellbookChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * B17's gate: every Tier A and B kind of transport, bank stop and spellbook change shows up in at least one golden
 * route. Counts every shown route (Best, Direct, Same book, alternatives) of every case, with the first case per kind.
 */
final class GoldenCoverage
{
	static final List<String> KINDS = List.of(
		"spell", "home", "minigame", "tablet", "scroll", "jewellery", "diary", "cape", "item",
		"fairy ring", "fairy ring from the house", "house teleport landing",
		"nexus", "jewellery box", "mounted", "portal", "obelisk",
		"spirit tree from the house", "spirit tree", "planted spirit tree", "spirit tree to the house",
		"quetzal", "quetzal whistle",
		"bank stop", "group storage withdraw",
		"spellbook swap", "cape change", "walked change (altar, Tyss)", "house altar or mounted cape");

	private final Map<String, Integer> counts = new TreeMap<>();
	private final Map<String, String> firstCase = new TreeMap<>();

	void add(String caseName, Plan plan)
	{
		List<Route> routes = new ArrayList<>();
		routes.add(plan.getBest());
		routes.add(plan.getDirect());
		routes.add(plan.getSameBook());
		routes.addAll(plan.getAlternatives());
		for (Route route : routes)
		{
			if (route != null)
			{
				route.getLegs().forEach(leg -> kindsOf(leg).forEach(kind -> count(kind, caseName)));
				if (route.getWithdraw().stream().anyMatch(use -> use.getContainer() == Container.GROUP_STORAGE))
				{
					count("group storage withdraw", caseName);
				}
			}
		}
	}

	/** One line per kind; MISSING where no golden route has it. */
	List<String> lines()
	{
		List<String> out = new ArrayList<>();
		for (String kind : KINDS)
		{
			Integer n = counts.get(kind);
			out.add(String.format("%-30s %s", kind, n == null ? "MISSING" : n + " routes, first: " + firstCase.get(kind)));
		}
		return out;
	}

	List<String> missing()
	{
		List<String> out = new ArrayList<>(KINDS);
		out.removeAll(counts.keySet());
		return out;
	}

	private void count(String kind, String caseName)
	{
		counts.merge(kind, 1, Integer::sum);
		firstCase.putIfAbsent(kind, caseName.split("\n")[0]);
	}

	private static List<String> kindsOf(Leg leg)
	{
		List<String> kinds = new ArrayList<>();
		switch (leg.getKind())
		{
			case BANK:
				kinds.add("bank stop");
				break;
			case SWAP_TELEPORT:
				kinds.add("spellbook swap");
				kinds.add(rowKind(leg.getRow()));
				break;
			case SPELLBOOK_CHANGE:
				kinds.add(changeKind(leg.getChange()));
				break;
			case FAIRY_RING:
				kinds.add(leg.getRow().isInHouse() ? "fairy ring from the house" : "fairy ring");
				break;
			case TELEPORT:
				kinds.add(rowKind(leg.getRow()));
				if (PositionRules.POH_NODE.equals(leg.getRow().getDestination()) && !leg.getRow().isLocated())
				{
					kinds.add("house teleport landing");
				}
				break;
			default:
				break;
		}
		return kinds;
	}

	private static String rowKind(CatalogRow row)
	{
		Category category = row.getCategory();
		if (category == Category.SPIRIT_TREE)
		{
			if (row.isInHouse())
			{
				return "spirit tree from the house";
			}
			if (PositionRules.POH_NODE.equals(row.getDestination()))
			{
				return "spirit tree to the house";
			}
			return row.getRequirements().getPlantedTrees().isEmpty() ? "spirit tree" : "planted spirit tree";
		}
		if (category == Category.ITEM && row.getName().startsWith("Quetzal whistle"))
		{
			return "quetzal whistle";
		}
		return category.name().toLowerCase().replace('_', ' ');
	}

	private static String changeKind(SpellbookChange change)
	{
		if (change.isAnywhere())
		{
			return "cape change";
		}
		return change.isInHouse() ? "house altar or mounted cape" : "walked change (altar, Tyss)";
	}
}
