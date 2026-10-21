package com.cluehelper.hotcold;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.Value;

/**
 * The panel's hot/cold card. The branch preview names only core's candidates, grouped by the reading each would give
 * at the probe: never a candidate list of our own.
 */
@Value
public class HotColdView
{
	static final int MOST_BRANCHES = 3;
	static final int NAMED_SPOTS = 2;
	/** The most a master feel dealt in B18's 13. */
	static final int MOST_FEEL_DAMAGE = 8;
	private static final int NAMED_AREAS = 2;

	String title;
	/** "Next feel" and the branch lines. */
	List<String> lines;
	List<String> warnings;

	@Value
	public static class Inputs
	{
		HotColdPlan plan;
		/** A copy of core's remaining spots. */
		Set<String> coreSpots;
		boolean coreReset;
		List<Spot> spots;
		boolean beginner;
		int hitpoints;
	}

	public static HotColdView of(Inputs in)
	{
		HotColdPlan plan = in.getPlan();
		if (plan == null)
		{
			return null;
		}
		Map<String, Spot> spots = in.getSpots().stream().collect(Collectors.toMap(Spot::getName, spot -> spot));
		List<String> lines = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		int kept = plan.getKept().size();
		String title;
		switch (plan.getAction())
		{
			case DIG_HERE:
				title = "The device shook: dig on that tile";
				break;
			case DIG_SPOT:
				title = "Only " + name(spots, plan.getKept().keySet().iterator().next()) + " fits";
				break;
			case NONE:
				title = kept + " spots fit, and no feel tells them apart";
				break;
			default:
				title = plan.getReadings() == 0 ? "No feels yet: " + kept + " spots" : kept + " spots fit your " + plan.getReadings() + feels(plan.getReadings());
				Probe probe = plan.getChoice().getProbe();
				lines.add(probe.getKind() == Probe.Kind.HERE ? "Feel where you stand:" : "Next feel, ≈ " + probe.getTicks() + " ticks away:");
				lines.addAll(branchLines(plan, in.getCoreSpots(), spots));
				break;
		}
		if (!in.isBeginner() && plan.getAction() == HotColdPlan.Action.FEEL && in.getHitpoints() <= MOST_FEEL_DAMAGE)
		{
			warnings.add("HP " + in.getHitpoints() + ": the next feel can kill you (master feels deal 4–8)");
		}
		if (!plan.isExact())
		{
			warnings.add("No spot fits every feel; these fit the most");
		}
		if (in.isCoreReset() && plan.getReadings() > 0)
		{
			warnings.add("The Clue Scroll plugin's list was reset; Clue Helper still counts your " + plan.getReadings() + feels(plan.getReadings()));
		}
		else if (!in.getCoreSpots().isEmpty() && !in.getCoreSpots().containsAll(plan.getKept().keySet()))
		{
			warnings.add("Core's list may have dropped the spot. Re-read the clue and feel again");
		}
		borderNote(plan, spots).ifPresent(warnings::add);
		return new HotColdView(title, List.copyOf(lines), List.copyOf(warnings));
	}

	private static Optional<String> borderNote(HotColdPlan plan, Map<String, Spot> spots)
	{
		boolean digging = plan.getAction() == HotColdPlan.Action.DIG_HERE || plan.getAction() == HotColdPlan.Action.DIG_SPOT;
		if (!digging || plan.getKept().isEmpty())
		{
			return Optional.empty();
		}
		Spot spot = spots.get(plan.getKept().keySet().iterator().next());
		return spot != null && spot.isBorder()
			? Optional.of("Dig on the single-combat side to face one Brassican Mage")
			: Optional.empty();
	}

	/** Each core candidate goes to the reading it most likely gives; hotter readings first. */
	static List<String> branchLines(HotColdPlan plan, Set<String> coreSpots, Map<String, Spot> spots)
	{
		List<ProbePlanner.Branch> branches = plan.branches();
		Map<ProbePlanner.Branch, List<String>> grouped = new LinkedHashMap<>();
		for (String name : plan.getKept().keySet())
		{
			if (!coreSpots.isEmpty() && !coreSpots.contains(name))
			{
				continue;
			}
			branches.stream()
				.filter(branch -> branch.getSpots().containsKey(name))
				.max(Comparator.comparingDouble(branch -> branch.getProbability() * branch.getSpots().get(name)))
				.ifPresent(branch -> grouped.computeIfAbsent(branch, b -> new ArrayList<>()).add(name));
		}
		List<ProbePlanner.Branch> order = new ArrayList<>(grouped.keySet());
		order.sort(Comparator.comparing((ProbePlanner.Branch b) -> b.getBand().ordinal())
			.thenComparing(b -> b.getComparison() == null ? -1 : b.getComparison().ordinal()));
		List<String> lines = new ArrayList<>();
		int others = 0;
		for (int i = 0; i < order.size(); i++)
		{
			ProbePlanner.Branch branch = order.get(i);
			if (i >= MOST_BRANCHES && order.size() > MOST_BRANCHES + 1)
			{
				others += grouped.get(branch).size();
				continue;
			}
			lines.add("If " + label(branch, order) + ": " + group(grouped.get(branch), spots));
		}
		if (others > 0)
		{
			lines.add("Else: " + others + " other" + (others == 1 ? "" : "s"));
		}
		return lines;
	}

	/** The comparison is named only where two shown branches share a band. */
	private static String label(ProbePlanner.Branch branch, List<ProbePlanner.Branch> shown)
	{
		long sameBand = shown.stream().filter(other -> other.getBand() == branch.getBand()).count();
		return sameBand > 1 ? branch.label() : branch.getBand().getText();
	}

	private static String group(List<String> names, Map<String, Spot> spots)
	{
		if (names.size() <= NAMED_SPOTS)
		{
			return names.stream().map(name -> name(spots, name)).collect(Collectors.joining("; "));
		}
		Map<String, Integer> areas = new TreeMap<>();
		for (String name : names)
		{
			Spot spot = spots.get(name);
			areas.merge(spot == null ? "?" : spot.getArea(), 1, Integer::sum);
		}
		String byArea = areas.entrySet().stream()
			.sorted((a, b) -> b.getValue() - a.getValue())
			.limit(NAMED_AREAS)
			.map(e -> e.getKey() + " " + e.getValue())
			.collect(Collectors.joining(", "));
		return names.size() + " spots (" + byArea + (areas.size() > NAMED_AREAS ? ", …" : "") + ")";
	}

	private static String name(Map<String, Spot> spots, String name)
	{
		Spot spot = spots.get(name);
		return spot == null ? name : spot.getDescription();
	}

	private static String feels(int readings)
	{
		return readings == 1 ? " feel" : " feels";
	}
}
