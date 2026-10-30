package com.cluehelper.route;

import com.cluehelper.player.*;
import com.cluehelper.step.*;
import com.cluehelper.transport.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

/** Pure, so it runs on the executor with snapshot data only. */
public final class Planner
{
	/** A route with a bank stop or a walked change must beat its reference by this much: near-ties stay on fewer steps. */
	private static final int MARGIN_HALF_TICKS = 4;
	/** A transport landing this near a target unreached is taken as its way in when the note says what is missing. */
	private static final int BLOCKED_RIDE_TILES = 50;
	/** The engine words each shortfall as "needs 25 x Ecto-token"; under a "needs:" heading the word goes. */
	private static final String NEEDS = "needs ";
	private static final String NOT_FOUND = "Not found on you or in your bank:";
	private static final String LEVELS_SHORT = "Levels too low:";
	private static final String QUESTS_UNFINISHED = "Quests not finished:";
	private static final String NOT_YET = "Not possible yet:";
	/** A hand-written need named with its other ways: "Chef's hat (or cooking cape, Max cape, Varrock armour 3)". */
	private static final Pattern OTHER_WAYS = Pattern.compile("(.+) \\(or (?:an? )?(.+)\\)");
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
			return Plan.builder().stepKey(step.getKey()).note("not guided: a step outside the normal game (Leagues, or removed)").build();
		}
		if (step == null || step.getTargets() == null || step.getTargets().isEmpty())
		{
			return Plan.builder().stepKey(step == null ? null : step.getKey()).note(step == null ? "no step"
				: ClueTypes.HOT_COLD.equals(step.getType()) ? "choosing where to feel" : "no target tile for this step yet").build();
		}
		List<String> shortLevels = shortLevels(step, in.getPlayer());
		if (!shortLevels.isEmpty())
		{
			return Plan.builder().stepKey(step.getKey()).requirementNotes(shortLevels).build();
		}
		PlanContext ctx = new PlanContext(in);
		if (ctx.stageVerdicts().stream().anyMatch(verdict -> !verdict.getMissing().isEmpty())
			|| ctx.required.getMissing().stream().anyMatch(RequiredItems.Need::isBlocksRoute))
		{
			return Plan.builder().stepKey(step.getKey()).requirementNotes(requirementNotes(ctx)).build();
		}
		List<List<int[]>> orders = legOrders(ctx, step);
		ctx.freezeNodes();
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
					.searchNanos(System.nanoTime() - started).labels(searches.labels + throughWilderness.getLabels()).build();
			}
		}
		plan.best(picks.best).direct(picks.direct != picks.best ? picks.direct : null)
			.sameBook(picks.best != null && picks.best.isChanged() ? picks.sameBook : null)
			.fasterUnqualified(picks.fasterUnqualified)
			.candidates(picks.candidates());
		Map<WorldPoint, Integer> probeRanks = new LinkedHashMap<>();
		plan.probeTicks(probes ? probeTicks(ctx, first, probeRanks) : Map.of()).probeRanks(probeRanks);
		plan.alternatives(probes || orders.size() > 1 || !withAlternatives ? List.of() : alternatives(in, searches, orders, picks));
		plan.followed(probes || orders.size() > 1 ? null : followed(in, searches, orders, picks));
		List<String> requirementNotes = requirementNotes(ctx);
		boolean missingNamed = requirementNotes.contains(NOT_FOUND);
		List<String> noRoute = probes || picks.best != null || missingNamed ? List.of() : noRouteNotes(ctx, orders);
		plan.note(!noRoute.isEmpty() ? noRoute.get(0) : picks.best != null && ctx.onlyInWilderness ? "Wilderness target" : null);
		List<String> notes = new ArrayList<>(noRoute.subList(Math.min(1, noRoute.size()), noRoute.size()));
		notes.addAll(requirementNotes);
		plan.requirementNotes(List.copyOf(notes));
		return plan.searchNanos(System.nanoTime() - started).labels(searches.labels).build();
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

	/** The expected search: half the area's radius, run. */
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
		return Running.halfTicks(radius / 2);
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
			Route pick = new Picks(Picks.routes(ctx, best)).best;
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
		Set<List<String>> needs = new LinkedHashSet<>();
		for (RequiredItems.Verdict verdict : verdicts)
		{
			for (RequiredItems.Need need : verdict.getMissing())
			{
				List<String> ways = new ArrayList<>(waysNamed(need.getName()));
				List<String> otherWay = verdict.getMissingParts().getOrDefault(need, List.of());
				if (!otherWay.isEmpty())
				{
					ways.add(String.join(" and ", otherWay));
				}
				needs.add(List.copyOf(ways));
			}
		}
		return section(NOT_FOUND, List.copyOf(needs));
	}

	/**
	 * A need's name split into its ways, as the list shows them: "Mirror shield (or V's shield)" and core's "Pure essence
	 * or Daeyalt essence" are two lines each.
	 */
	private static List<String> waysNamed(String name)
	{
		Matcher others = OTHER_WAYS.matcher(name);
		List<String> ways = new ArrayList<>();
		if (others.matches())
		{
			ways.add(others.group(1));
			ways.addAll(Arrays.asList(others.group(2).split(", ")));
		}
		else
		{
			Arrays.stream(name.split(",? or ")).forEach(ways::add);
		}
		return ways.stream().map(Planner::wayLine).collect(Collectors.toList());
	}

	/** A level the task needs and the player lacks: the step can't be done, so nothing routes to it. */
	private static List<String> shortLevels(Step step, PlayerSnapshot player)
	{
		List<String> tooLow = new ArrayList<>();
		for (StepIndex.Level level : step.getData().getLevels())
		{
			LevelNeed need = LevelNeed.ofSkill(level.getSkill(), level.getLevel());
			int have = levelHad(player, level.getSkill());
			if (have < need.getLevel())
			{
				tooLow.add(need.describe() + " (has " + have + "; boosts count)");
			}
		}
		return section(LEVELS_SHORT, each(tooLow));
	}

	/** Prayer's boosted level is its points left: a drained level comes back, so only a boost raises it. */
	private static int levelHad(PlayerSnapshot player, Skill skill)
	{
		return Math.max(player.boosted(skill), player.getRealLevels().getOrDefault(skill, 1));
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

	/**
	 * The note on a step with no route, then what the player lacks for it listed as missing items are. A target with no
	 * walk node, or one no entry within the table's 200-tile walks reaches, is a gap in the walk data, not the player's.
	 */
	private static List<String> noRouteNotes(PlanContext ctx, List<List<int[]>> orders)
	{
		Step step = ctx.in.getStep();
		if (step.getData().getReach() != null)
		{
			return List.of(step.getData().getReach());
		}
		WalkTable table = ctx.table;
		Set<Integer> targets = orders.get(0).stream().flatMapToInt(Arrays::stream).boxed().collect(Collectors.toSet());
		boolean noWalkData = targets.stream().anyMatch(n -> n == WalkTable.NO_NODE || table.reverseEnd(n) == table.reverseFrom(n));
		if (noWalkData)
		{
			return List.of("No route: the plugin has no way to this spot yet");
		}
		Set<String> quests = lockingQuests(ctx, targets);
		if (!quests.isEmpty())
		{
			return section(QUESTS_UNFINISHED, List.of(List.copyOf(quests)));
		}
		List<List<String>> items = lockingItems(ctx, targets);
		return !items.isEmpty() ? section(NOT_FOUND, items) : blockedRideNotes(ctx);
	}

	/**
	 * The items owned nowhere that shut every way into the targets, each with its ways round (the sled down Trollweiss
	 * Mountain): only gates that items alone keep shut.
	 */
	private static List<List<String>> lockingItems(PlanContext ctx, Set<Integer> targets)
	{
		PlayerSnapshot judged = ctx.in.getBanked() != null ? ctx.in.getBanked() : ctx.in.getPlayer();
		WalkTable table = ctx.table;
		WalkGates gates = ctx.gates(PlanContext.BANKED);
		Set<List<String>> missing = new LinkedHashSet<>();
		for (int target : targets)
		{
			for (int i = table.reverseFrom(target); i < table.reverseEnd(target); i++)
			{
				gates.shutWays(table.reverseEntry(i)).stream().flatMap(List::stream)
					.map(gate -> ctx.in.getEngine().judge(CatalogRow.builder().name("gate").requirements(gate).build(), judged,
						ctx.in.getNowMinutes()))
					.filter(verdict -> !verdict.isAvailable() && verdict.getMissingItems().size() == verdict.getWhyNot().size())
					.forEach(verdict -> missing.addAll(verdict.getMissingItems()));
			}
		}
		return List.copyOf(missing);
	}

	/** The way in the player lacks, from the nearest transport landing by a target (the Dragontooth boat's fare). */
	private static List<String> blockedRideNotes(PlanContext ctx)
	{
		PlayerSnapshot judged = ctx.in.getBanked() != null ? ctx.in.getBanked() : ctx.in.getPlayer();
		List<WorldPoint> targets = ctx.in.getStep().getTargets();
		return ctx.in.getCatalog().getTeleports().stream()
			.filter(row -> row.getDestination() != null && distanceToNearest(row.getDestination(), targets) <= BLOCKED_RIDE_TILES)
			.sorted(Comparator.comparingInt(row -> distanceToNearest(row.getDestination(), targets)))
			.map(row -> ctx.in.getEngine().judge(row, judged, ctx.in.getNowMinutes()))
			.filter(verdict -> !verdict.isAvailable() && !verdict.getWhyNot().isEmpty())
			.findFirst()
			.map(Planner::blockedRideLines)
			.orElse(List.of("no route from what you have"));
	}

	/** What shuts the ride, as every missing note reads: items, levels, quests, then anything else. */
	private static List<String> blockedRideLines(RowAvailability verdict)
	{
		List<String> lines = new ArrayList<>(section(NOT_FOUND, verdict.getMissingItems()));
		lines.addAll(section(LEVELS_SHORT, each(verdict.getMissingLevels())));
		lines.addAll(section(QUESTS_UNFINISHED, each(verdict.getMissingQuests())));
		lines.addAll(section(NOT_YET, each(verdict.getOtherReasons())));
		return lines;
	}

	/**
	 * Every missing note's form: a heading, then a line per need, each need's ways round it split by "or" ("25 x
	 * Ecto-token", or "The dragontooth unlock").
	 */
	private static List<String> section(String heading, List<List<String>> needs)
	{
		if (needs.isEmpty())
		{
			return List.of();
		}
		List<String> lines = new ArrayList<>(List.of(heading));
		needs.forEach(ways -> lines.addAll(alternativeLines(ways.stream().map(Planner::wayLine).collect(Collectors.toList()))));
		return lines;
	}

	/** Needs with one way each, all of them wanted. */
	private static List<List<String>> each(List<String> needs)
	{
		return needs.stream().map(List::of).collect(Collectors.toList());
	}

	private static String wayLine(String way)
	{
		String bare = way.startsWith(NEEDS) ? way.substring(NEEDS.length()) : way;
		return bare.isEmpty() ? bare : Character.toUpperCase(bare.charAt(0)) + bare.substring(1);
	}

	private static List<String> alternativeLines(List<String> ways)
	{
		List<String> lines = new ArrayList<>();
		for (String way : ways)
		{
			if (!lines.isEmpty())
			{
				lines.add("or");
			}
			lines.add("- " + way);
		}
		return lines;
	}

	private static int distanceToNearest(WorldPoint tile, List<WorldPoint> targets)
	{
		return targets.stream().filter(target -> target.getPlane() == tile.getPlane()).mapToInt(target -> target.distanceTo2D(tile))
			.min().orElse(Integer.MAX_VALUE);
	}

	/**
	 * Regicide, or Mourning's End Part I: the unfinished quests on each shut way into the targets. Open walks into them
	 * don't clear it: inside a gated city they start from landings nothing reached. None while quests aren't read, since
	 * every quest gate fails until then.
	 */
	private static Set<String> lockingQuests(PlanContext ctx, Set<Integer> targets)
	{
		PlayerSnapshot player = ctx.in.getPlayer();
		if (!player.isQuestsRead())
		{
			return Set.of();
		}
		WalkTable table = ctx.table;
		WalkGates gates = ctx.gates(PlanContext.NOT_BANKED);
		Set<String> ways = new TreeSet<>();
		for (int target : targets)
		{
			for (int i = table.reverseFrom(target); i < table.reverseEnd(target); i++)
			{
				gates.shutWays(table.reverseEntry(i)).stream().map(way -> unfinishedQuests(way, player)).filter(quests -> !quests.isEmpty())
					.forEach(ways::add);
			}
		}
		return ways;
	}

	private static String unfinishedQuests(List<Requirements> gates, PlayerSnapshot player)
	{
		return gates.stream().flatMap(gate -> gate.getQuests().stream()).filter(quest -> !player.finished(quest))
			.map(Quest::getName).distinct().sorted().collect(Collectors.joining(" and "));
	}

	private static final class Searches
	{
		final PlanContext ctx;
		final int area;
		final boolean probes;
		int labels;

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
			return search;
		}
	}

	static final class Picks
	{
		final Route[][] routes;
		Route best;
		Route direct;
		Route sameBook;
		Route fasterUnqualified;

		Picks(PlanContext ctx, LabelSearch search)
		{
			this(routes(ctx, search.goals));
		}

		Picks(Route[][] r)
		{
			routes = r;
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

		/** Every goal route, by bank state and change category. */
		List<Plan.Candidate> candidates()
		{
			List<Plan.Candidate> candidates = new ArrayList<>();
			for (int b = 0; b < 2; b++)
			{
				for (int c = 0; c < LabelSearch.CATEGORIES; c++)
				{
					if (routes[b][c] != null)
					{
						candidates.add(new Plan.Candidate(category(b, c), routes[b][c]));
					}
				}
			}
			return candidates;
		}

		static Route[][] routes(PlanContext ctx, LabelSearch.Label[][] goals)
		{
			Route[][] routes = new Route[2][LabelSearch.CATEGORIES];
			for (int b = 0; b < 2; b++)
			{
				for (int c = 0; c < LabelSearch.CATEGORIES; c++)
				{
					LabelSearch.Label goal = goals[b][c];
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
			return route.getRankHalfTicks() < reference.getRankHalfTicks() - MARGIN_HALF_TICKS;
		}
	}
}
