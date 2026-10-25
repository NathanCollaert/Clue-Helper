package com.cluehelper.route;

import com.cluehelper.player.PositionRules;
import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.Stage;
import com.cluehelper.step.Step;
import com.cluehelper.step.TargetKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.coords.WorldPoint;

/** Pure, so it runs on the executor with snapshot data only. */
public final class Planner
{
	/** A route with a bank stop or a walked change must beat its reference by this margin. */
	private static final int MARGIN_MIN_HALF_TICKS = 10;
	private static final int MARGIN_PERCENT = 10;
	private static final int REGION_SIZE = 64;
	private static final int ALTERNATIVE_SLACK_HALF_TICKS = 60;
	/** Catalog landings of one place spread 0–2 tiles. */
	private static final int SAME_LANDING_TILES = 2;
	/** Rejected repeats and round trips count against this too. */
	private static final int MOST_ALTERNATIVE_SEARCHES = 12;

	private Planner()
	{
	}

	public static Plan plan(PlanInput in)
	{
		return plan(in, true);
	}

	/**
	 * Without the alternatives, whose searches take most of a plan's time; the followed route's way is planned again
	 * instead, bounded by the best route plus the commit margin.
	 */
	public static Plan withoutAlternatives(PlanInput in)
	{
		return plan(in, false);
	}

	private static Plan plan(PlanInput in, boolean withAlternatives)
	{
		long started = System.nanoTime();
		Step step = in.getStep();
		if (step != null && step.getData().isHidden())
		{
			return Plan.builder().stepKey(step.getKey()).alternatives(List.of()).candidates(List.of()).probeTicks(Map.of())
				.note("not guided: a Leagues step").build();
		}
		if (step == null || step.getTargets() == null || step.getTargets().isEmpty())
		{
			return Plan.builder().stepKey(step == null ? null : step.getKey()).alternatives(List.of()).candidates(List.of())
				.probeTicks(Map.of()).note(step == null ? "no step" : ClueTypes.HOT_COLD.equals(step.getType())
					? "choosing where to feel" : "no target tile for this step yet").build();
		}
		PlanContext ctx = new PlanContext(in);
		if (ctx.stageVerdicts().stream().anyMatch(verdict -> !verdict.getMissing().isEmpty()))
		{
			return Plan.builder().stepKey(step.getKey()).alternatives(List.of()).candidates(List.of()).probeTicks(Map.of())
				.requirementNotes(requirementNotes(ctx)).build();
		}
		List<List<int[]>> orders = legOrders(ctx, step);
		ctx.freezeNodes();
		long contextNanos = System.nanoTime() - started;
		int area = step.getKind() == TargetKind.AREA ? areaHalfTicks(step) : 0;
		boolean probes = step.getKind() == TargetKind.PROBES;
		Searches searches = new Searches(ctx, area, probes);

		LabelSearch first = searches.bestOrder(orders, Set.of(), Integer.MAX_VALUE);
		Plan.PlanBuilder plan = Plan.builder().stepKey(step.getKey());
		Picks picks = new Picks(ctx, first);
		if (picks.best == null && ctx.avoidsWilderness && !probes)
		{
			Plan throughWilderness = plan(wildernessAllowed(in), withAlternatives);
			if (throughWilderness.getBest() != null)
			{
				return throughWilderness.toBuilder().note("only reachable through the Wilderness")
					.searchNanos(System.nanoTime() - started).labels(searches.labels + throughWilderness.getLabels())
					.searches(searches.count + throughWilderness.getSearches()).build();
			}
		}
		plan.best(picks.best).direct(picks.direct != picks.best ? picks.direct : null)
			.sameBook(picks.best != null && picks.best.isChanged() ? picks.sameBook : null)
			.fasterUnqualified(picks.fasterUnqualified)
			.candidates(picks.candidates);
		Map<WorldPoint, Integer> probeRanks = new LinkedHashMap<>();
		plan.probeTicks(probes ? probeTicks(ctx, first, probeRanks) : Map.of()).probeRanks(probeRanks);
		plan.alternatives(probes || orders.size() > 1 || !withAlternatives ? List.of() : alternatives(in, searches, orders, picks));
		plan.followed(probes || orders.size() > 1 ? null : followed(in, searches, orders, picks));
		plan.note(probes ? null : picks.best == null ? noRouteNote(ctx, orders) : ctx.onlyInWilderness ? "Wilderness target" : null);
		plan.requirementNotes(requirementNotes(ctx));
		return plan.searchNanos(System.nanoTime() - started).contextNanos(contextNanos).labels(searches.labels)
			.searches(searches.count).build();
	}

	/** Avoiding the Wilderness never hides the only way to a step outside it, such as the King Black Dragon's lair. */
	private static PlanInput wildernessAllowed(PlanInput in)
	{
		return in.toBuilder().settings(in.getSettings().toBuilder().avoidWilderness(false).build()).build();
	}

	private static List<List<int[]>> legOrders(PlanContext ctx, Step step)
	{
		if (!step.getStages().isEmpty())
		{
			return stageOrders(ctx, step);
		}
		List<int[]> nodes = step.getTargets().stream().map(tile -> new int[]{ctx.nodeAt(tile)}).collect(Collectors.toList());
		if (step.getKind() != TargetKind.SEQUENCE)
		{
			return List.of(List.of(nodes.stream().mapToInt(n -> n[0]).toArray()));
		}
		if (!ClueTypes.THREE_STEP.equals(step.getType()))
		{
			return List.of(nodes);
		}
		List<List<int[]>> orders = new ArrayList<>();
		permute(nodes, 0, orders);
		return orders;
	}

	/**
	 * One leg per stage, any of its tiles. A stage done anywhere (Sherlock's task with no place) has no leg. Three-step
	 * parts may be visited in any order; every other step's stages keep theirs.
	 */
	private static List<List<int[]>> stageOrders(PlanContext ctx, Step step)
	{
		List<int[]> legs = new ArrayList<>();
		boolean anyOrder = true;
		for (Stage stage : step.getStages())
		{
			anyOrder &= stage.getKind() == Stage.Kind.PART;
			int[] places = stage.getTiles().stream().filter(tile -> isThere(ctx, stage, tile)).mapToInt(ctx::nodeAt).toArray();
			if (places.length > 0)
			{
				legs.add(places);
			}
		}
		if (!anyOrder)
		{
			return List.of(legs);
		}
		List<List<int[]>> orders = new ArrayList<>();
		permute(legs, 0, orders);
		return orders;
	}

	/**
	 * A place that needs a grown patch (a Farming Guild redwood) counts only while the player has it grown, one in the
	 * house (its spirit tree) only while the house has it.
	 */
	private static boolean isThere(PlanContext ctx, Stage stage, WorldPoint tile)
	{
		String patch = stage.getGrownOnly().get(tile);
		boolean inHouse = PositionRules.POH_NODE.equals(tile);
		return (patch == null || ctx.in.getPlayer().getPlantedTrees().contains(patch))
			&& (!inHouse || ctx.in.getPlayer().getHouse().hasAny(stage.getHouseObjects())) && !ctx.lacksItems(tile);
	}

	private static void permute(List<int[]> items, int from, List<List<int[]>> out)
	{
		if (from == items.size())
		{
			out.add(new ArrayList<>(items));
			return;
		}
		for (int i = from; i < items.size(); i++)
		{
			Collections.swap(items, from, i);
			permute(items, from + 1, out);
			Collections.swap(items, from, i);
		}
	}

	/** The expected search: half the area's radius, run at one tile per half-tick. */
	static int areaHalfTicks(Step step)
	{
		List<Integer> regions = step.getRegions();
		if (regions == null || regions.isEmpty())
		{
			return 0;
		}
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		for (int region : regions)
		{
			int x = (region >> 8) * REGION_SIZE;
			int y = (region & 0xFF) * REGION_SIZE;
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			maxX = Math.max(maxX, x + REGION_SIZE);
			maxY = Math.max(maxY, y + REGION_SIZE);
		}
		int radius = Math.max(maxX - minX, maxY - minY) / 2;
		return radius / 2;
	}

	private static Route followed(PlanInput in, Searches searches, List<List<int[]>> orders, Picks picks)
	{
		String first = in.getFollowedFirstTransport();
		if (first == null || picks.best == null || first.equals(picks.best.getFirstTransportKey()))
		{
			return null;
		}
		int limit = picks.best.getRankHalfTicks() + 2 * RoutePlanner.COMMIT_MARGIN_TICKS;
		LabelSearch search = searches.firstBy(orders, first, limit);
		return search == null ? null : new Picks(searches.ctx, search).best;
	}

	private static List<Route> alternatives(PlanInput in, Searches searches, List<List<int[]>> orders, Picks picks)
	{
		List<Route> alternatives = new ArrayList<>();
		Set<String> excluded = new HashSet<>();
		Set<String> identities = new HashSet<>();
		List<Route> shown = new ArrayList<>();
		Stream.of(picks.best, picks.direct, picks.sameBook, picks.fasterUnqualified).filter(Objects::nonNull).forEach(route ->
		{
			excluded.add(route.getFirstTransportKey());
			excludeAfterBank(route, excluded);
			identities.add(route.getIdentity());
			shown.add(route);
		});
		if (excluded.isEmpty())
		{
			return alternatives;
		}
		int limit = picks.best == null ? Integer.MAX_VALUE
			: picks.best.getRankHalfTicks() + Math.max(picks.best.getRankHalfTicks(), ALTERNATIVE_SLACK_HALF_TICKS);
		for (int tries = 0; alternatives.size() < in.getSettings().getRouteCount() - 1 && tries < MOST_ALTERNATIVE_SEARCHES; tries++)
		{
			LabelSearch search = searches.bestOrder(orders, Set.copyOf(excluded), limit);
			Route next = new Picks(searches.ctx, search).best;
			if (next == null || !excluded.add(next.getFirstTransportKey()))
			{
				break;
			}
			excludeAfterBank(next, excluded);
			if (!repeats(next, shown) && !loops(next) && identities.add(next.getIdentity()))
			{
				alternatives.add(next);
				shown.add(next);
			}
		}
		return alternatives;
	}

	private static void excludeAfterBank(Route route, Set<String> excluded)
	{
		String afterBank = route.getAfterBankTransportKey();
		if (afterBank != null)
		{
			excluded.add(afterBank);
		}
	}

	/**
	 * Leaving the shown routes' transports out can leave a round trip as the cheapest route with a transport ("walk to
	 * ring BIP, ring to DIP, ring back to BIP, walk 35").
	 */
	private static boolean loops(Route route)
	{
		List<WorldPoint> places = new ArrayList<>();
		for (Leg leg : route.getLegs())
		{
			WorldPoint from = leg.getFrom();
			WorldPoint to = leg.getTo();
			if (from == null || to == null || to.equals(from))
			{
				continue;
			}
			if (places.isEmpty())
			{
				places.add(from);
			}
			List<WorldPoint> before = places.subList(0, places.size() - 1);
			if (before.stream().anyMatch(seen -> seen.getPlane() == to.getPlane() && seen.distanceTo2D(to) <= SAME_LANDING_TILES))
			{
				return true;
			}
			places.add(to);
		}
		return false;
	}

	private static WorldPoint firstLanding(Route route)
	{
		Leg first = route.getFirstTransport();
		return first == null ? null : first.getTo();
	}

	/**
	 * Fishing cape and Max cape to Otto's Grotto are one alternative, while a free home teleport beside a paid spell
	 * stays.
	 */
	private static boolean repeats(Route route, List<Route> shown)
	{
		WorldPoint landing = firstLanding(route);
		return landing != null && shown.stream().anyMatch(other ->
		{
			WorldPoint at = firstLanding(other);
			return at != null && at.getPlane() == landing.getPlane() && at.distanceTo2D(landing) <= SAME_LANDING_TILES
				&& route.getSpent().keySet().containsAll(other.getSpent().keySet());
		});
	}

	/** Fills {@code ranks} with each tile's ranking ticks, rounded up. */
	private static Map<WorldPoint, Integer> probeTicks(PlanContext ctx, LabelSearch search, Map<WorldPoint, Integer> ranks)
	{
		Map<WorldPoint, Integer> ticks = new LinkedHashMap<>();
		search.probeBest.forEach((node, best) ->
		{
			Route[][] routes = new Route[2][LabelSearch.CATEGORIES];
			for (int b = 0; b < 2; b++)
			{
				for (int c = 0; c < LabelSearch.CATEGORIES; c++)
				{
					LabelSearch.Label arrival = best[b][c];
					routes[b][c] = arrival == null ? null : RouteBuilder.build(ctx, arrival, arrival.rank);
				}
			}
			Route pick = new Picks(routes).best;
			if (pick != null)
			{
				ticks.put(ctx.tile(node), pick.getTicks());
				ranks.put(ctx.tile(node), (pick.getRankHalfTicks() + 1) / 2);
			}
		});
		return ticks;
	}

	/** One line per missing item; an item with another way to it lists that way's missing parts after an "or". */
	private static List<String> missingList(List<RequiredItems.Verdict> verdicts)
	{
		List<String> lines = new ArrayList<>();
		for (RequiredItems.Verdict verdict : verdicts)
		{
			for (RequiredItems.Need need : verdict.getMissing())
			{
				if (lines.contains("- " + need.getName()))
				{
					continue;
				}
				lines.add("- " + need.getName());
				List<String> parts = verdict.getMissingParts().getOrDefault(need, List.of());
				if (!parts.isEmpty())
				{
					lines.add("or");
					parts.forEach(part -> lines.add("- " + part));
				}
			}
		}
		if (!lines.isEmpty())
		{
			lines.add(0, "Not found on you or in your bank:");
		}
		return List.copyOf(lines);
	}

	static List<String> requirementNotes(PlanContext ctx)
	{
		RequiredItems required = ctx.in.getRequired();
		List<String> notes = new ArrayList<>();
		List<RequiredItems.Verdict> verdicts = new ArrayList<>(List.of(ctx.required));
		verdicts.addAll(ctx.stageVerdicts());
		notes.addAll(missingList(verdicts));
		if (required.isSpadeAtLeprechaun() && ctx.required.getMissing().stream().anyMatch(need -> need.getName().equalsIgnoreCase("spade")))
		{
			notes.add("Your spade is with a tool leprechaun");
		}
		return notes;
	}

	/** The walk table holds walks up to 200 tiles only, so a target no entry reaches is too far to walk. */
	private static String noRouteNote(PlanContext ctx, List<List<int[]>> orders)
	{
		Step step = ctx.in.getStep();
		if (step.getData().getReach() != null)
		{
			return step.getData().getReach();
		}
		Set<Integer> reachable = new HashSet<>();
		WalkTable table = ctx.table;
		for (int node = 0; node < table.nodeCount(); node++)
		{
			for (int e = table.entriesFrom(node); e < table.entriesEnd(node); e++)
			{
				reachable.add(table.entryTo(e));
			}
		}
		boolean walkTooLong = orders.get(0).stream().flatMapToInt(Arrays::stream).anyMatch(n -> !reachable.contains(n));
		return walkTooLong ? "walk too long" : "no route from what you have";
	}

	private static final class Searches
	{
		final PlanContext ctx;
		final int area;
		final boolean probes;
		int labels;
		int count;

		Searches(PlanContext ctx, int area, boolean probes)
		{
			this.ctx = ctx;
			this.area = area;
			this.probes = probes;
		}

		/**
		 * Each order after the first stops at the best rank so far, which leaves out its slower direct and same-book
		 * routes; so a bounded winner runs once more in full.
		 */
		LabelSearch bestOrder(List<List<int[]>> orders, Set<String> excluded, int rankLimit)
		{
			LabelSearch best = null;
			List<int[]> bestOrder = null;
			int bestRank = Integer.MAX_VALUE;
			boolean bestBounded = false;
			for (List<int[]> order : orders)
			{
				int limit = Math.min(rankLimit, bestRank);
				LabelSearch search = run(order, excluded, limit);
				Route pick = new Picks(ctx, search).best;
				int rank = pick == null ? Integer.MAX_VALUE : pick.getRankHalfTicks();
				if (best == null || rank < bestRank)
				{
					best = search;
					bestOrder = order;
					bestRank = rank;
					bestBounded = limit != rankLimit;
				}
			}
			return bestBounded ? run(bestOrder, excluded, rankLimit) : best;
		}

		/** The best order's search of the routes that start with transport {@code first}; null when none is in the limit. */
		LabelSearch firstBy(List<List<int[]>> orders, String first, int rankLimit)
		{
			LabelSearch best = null;
			int bestRank = Integer.MAX_VALUE;
			for (List<int[]> order : orders)
			{
				LabelSearch search = new LabelSearch(ctx, order, area, first, rankLimit);
				search.run();
				labels += search.labels;
				count++;
				Route pick = new Picks(ctx, search).best;
				if (pick != null && pick.getRankHalfTicks() < bestRank)
				{
					best = search;
					bestRank = pick.getRankHalfTicks();
				}
			}
			return best;
		}

		private LabelSearch run(List<int[]> order, Set<String> excluded, int rankLimit)
		{
			LabelSearch search = new LabelSearch(ctx, order, probes, area, excluded, rankLimit);
			search.run();
			labels += search.labels;
			count++;
			return search;
		}
	}

	static final class Picks
	{
		Route best;
		Route direct;
		Route sameBook;
		Route fasterUnqualified;
		List<Plan.Candidate> candidates = new ArrayList<>();

		Picks(PlanContext ctx, LabelSearch search)
		{
			this(routes(ctx, search));
			for (int b = 0; b < 2; b++)
			{
				for (int c = 0; c < LabelSearch.CATEGORIES; c++)
				{
					LabelSearch.Label goal = search.goals[b][c];
					if (goal != null)
					{
						candidates.add(new Plan.Candidate(category(b, c), RouteBuilder.build(ctx, goal, goal.rank)));
					}
				}
			}
		}

		Picks(Route[][] r)
		{
			Route plain = faster(r[0][LabelSearch.NONE], r[0][LabelSearch.CHANGED]);
			Route bankOnly = faster(r[1][LabelSearch.NONE], r[1][LabelSearch.CHANGED]);
			Route walkedOnly = r[0][LabelSearch.WALKED];
			Route both = r[1][LabelSearch.WALKED];
			Route pickBank = qualifies(bankOnly, plain) ? bankOnly : plain;
			Route pickWalked = qualifies(walkedOnly, plain) ? walkedOnly : plain;
			Route reference = faster(pickBank, pickWalked);
			Route pickBoth = qualifies(both, reference) ? both : reference;
			best = Stream.of(plain, pickBank, pickWalked, pickBoth).filter(Objects::nonNull)
				.min(Comparator.comparingInt(Route::getRankHalfTicks)).orElse(null);
			direct = pickWalked;
			Route sameBookBanked = r[1][LabelSearch.NONE];
			sameBook = qualifies(sameBookBanked, r[0][LabelSearch.NONE]) ? sameBookBanked : r[0][LabelSearch.NONE];
			Route fastest = Stream.of(r[0], r[1]).flatMap(Stream::of).filter(Objects::nonNull)
				.min(Comparator.comparingInt(Route::getRankHalfTicks)).orElse(null);
			fasterUnqualified = fastest != null && best != null && fastest.getRankHalfTicks() < best.getRankHalfTicks() ? fastest : null;
		}

		private static Route[][] routes(PlanContext ctx, LabelSearch search)
		{
			Route[][] routes = new Route[2][LabelSearch.CATEGORIES];
			for (int b = 0; b < 2; b++)
			{
				for (int c = 0; c < LabelSearch.CATEGORIES; c++)
				{
					LabelSearch.Label goal = search.goals[b][c];
					routes[b][c] = goal == null ? null : RouteBuilder.build(ctx, goal, goal.rank);
				}
			}
			return routes;
		}

		private static String category(int b, int c)
		{
			return (b == 1 ? "bank" : "no bank") + ", " + (c == LabelSearch.NONE ? "no change" : c == LabelSearch.CHANGED ? "carried change" : "walked change");
		}

		private static Route faster(Route a, Route b)
		{
			return a == null ? b : b == null ? a : b.getRankHalfTicks() < a.getRankHalfTicks() ? b : a;
		}

		private static boolean qualifies(Route route, Route reference)
		{
			if (route == null)
			{
				return false;
			}
			if (reference == null)
			{
				return true;
			}
			int margin = Math.max(MARGIN_MIN_HALF_TICKS, reference.getRankHalfTicks() * MARGIN_PERCENT / 100);
			return route.getRankHalfTicks() < reference.getRankHalfTicks() - margin;
		}
	}
}
