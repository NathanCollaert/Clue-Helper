package com.cluehelper.route;

import com.cluehelper.player.*;
import com.cluehelper.step.*;
import com.cluehelper.transport.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import static java.util.stream.Collectors.*;

/** Pure, so it runs on the executor with snapshot data only. */
public final class Planner
{
	/** A transport landing this near a target unreached is taken as its way in when the note says what is missing. */
	private static final int BLOCKED_RIDE_TILES = 50;
	/** The engine words each shortfall as "needs 25 x Ecto-token"; under a "needs:" heading the word goes. */
	private static final String NEEDS = "needs ";
	private static final String NOT_FOUND = "Required but not found:";
	private static final String LEVELS_SHORT = "Levels too low:";
	private static final String QUESTS_UNFINISHED = "Quests not finished:";
	private static final String NOT_YET = "Not possible yet:";
	private static final String THROUGH_WILDERNESS = "only reachable through the Wilderness";
	private static final String STEP_IS = "No guide: this clue step is ";
	/** A hand-written need named with its other ways: "Chef's hat (or cooking cape, Max cape, Varrock armour 3)". */
	private static final Pattern OTHER_WAYS = Pattern.compile("(.+) \\(or (?:an? )?(.+)\\)");
	private static final int REGION_SIZE = 64;

	private Planner()
	{
	}

	public static Plan plan(PlanInput in)
	{
		long started = System.nanoTime();
		Step step = in.getStep();
		Plan.PlanBuilder plan = Plan.builder().stepKey(step == null ? null : step.getKey());
		if (step != null && step.getData().isHidden())
		{
			return plan.note("not guided: a step outside the normal game (Leagues, or removed)").build();
		}
		if (step == null || step.getTargets().isEmpty())
		{
			return plan.note(step == null ? "no step"
				: ClueTypes.HOT_COLD.equals(step.getType()) ? "choosing where to feel" : "no target tile for this step yet").build();
		}
		PlanContext ctx = new PlanContext(in);
		if (in.getSettings().isStopWildernessClues() && ctx.onlyInWilderness)
		{
			return plan.danger(STEP_IS + "in the Wilderness").build();
		}
		List<String> shortLevels = shortLevels(step, in.getPlayer());
		if (!shortLevels.isEmpty())
		{
			return plan.requirementNotes(shortLevels).build();
		}
		if (ctx.stageVerdicts().stream().anyMatch(verdict -> !verdict.getMissing().isEmpty())
			|| ctx.required.getMissing().stream().anyMatch(RequiredItems.Need::isBlocksRoute))
		{
			return plan.requirementNotes(requirementNotes(ctx)).build();
		}
		List<List<int[]>> orders = legOrders(ctx, step);
		ctx.freezeNodes();
		int area = step.getKind() == TargetKind.AREA ? areaHalfTicks(step) : 0;
		boolean probes = step.getKind() == TargetKind.PROBES;
		Searches searches = new Searches(ctx, area, probes);

		LabelSearch first = searches.bestOrder(orders, probes ? probeRankLimitHalfTicks(in) : Integer.MAX_VALUE);
		Route best = RouteBuilder.build(ctx, first.goal);
		if (best == null && ctx.avoidsWilderness && !probes)
		{
			Plan throughWilderness = plan(wildernessAllowed(in));
			if (throughWilderness.getBest() != null)
			{
				Plan.PlanBuilder reached = in.getSettings().isStopWildernessClues() ? plan.danger(STEP_IS + THROUGH_WILDERNESS)
					: throughWilderness.toBuilder().note(THROUGH_WILDERNESS);
				return reached.searchNanos(System.nanoTime() - started).labels(searches.labels + throughWilderness.getLabels()).build();
			}
		}
		plan.best(best);
		Map<WorldPoint, Integer> probeRanks = new LinkedHashMap<>();
		plan.probeTicks(probes ? probeTicks(ctx, first, probeRanks) : Map.of()).probeRanks(probeRanks);
		plan.followed(probes || orders.size() > 1 ? null : followed(in, searches, orders, first.goal, best));
		List<String> requirementNotes = requirementNotes(ctx);
		boolean missingNamed = requirementNotes.contains(NOT_FOUND);
		List<String> noRoute = probes || best != null || missingNamed ? List.of() : noRouteNotes(ctx, orders);
		plan.note(!noRoute.isEmpty() ? noRoute.get(0) : best != null && ctx.onlyInWilderness ? "Wilderness target" : null);
		List<String> notes = new ArrayList<>(noRoute.subList(Math.min(1, noRoute.size()), noRoute.size()));
		notes.addAll(requirementNotes);
		plan.requirementNotes(List.copyOf(notes));
		return plan.searchNanos(System.nanoTime() - started).labels(searches.labels).build();
	}

	/** Unless Wilderness clues are stopped, avoiding the Wilderness never hides the only way to a step outside it. */
	private static PlanInput wildernessAllowed(PlanInput in)
	{
		return in.toBuilder().settings(in.getSettings().toBuilder().avoidWilderness(false).stopWildernessClues(false).build()).build();
	}

	private static List<List<int[]>> legOrders(PlanContext ctx, Step step)
	{
		if (!step.getStages().isEmpty())
		{
			return stageOrders(ctx, step);
		}
		if (step.getKind() != TargetKind.SEQUENCE)
		{
			return List.of(List.of(step.getTargets().stream().mapToInt(ctx::nodeAt).toArray()));
		}
		List<int[]> nodes = step.getTargets().stream().map(tile -> new int[]{ctx.nodeAt(tile)}).collect(toList());
		return ClueTypes.THREE_STEP.equals(step.getType()) ? permutations(nodes) : List.of(nodes);
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
			int[] places = notGrowing(ctx, stage.getTiles().stream().filter(tile -> isThere(ctx, stage, tile)).collect(toList()))
				.stream().mapToInt(ctx::nodeAt).toArray();
			if (places.length > 0)
			{
				legs.add(places);
			}
		}
		return anyOrder ? permutations(legs) : List.of(legs);
	}

	/**
	 * A place that needs a grown patch counts only while the player has it grown, one in the
	 * house (its spirit tree) only while the house has it.
	 */
	private static boolean isThere(PlanContext ctx, Stage stage, WorldPoint tile)
	{
		String patch = stage.getGrownOnly().get(tile);
		boolean inHouse = PositionRules.POH_NODE.equals(tile);
		return (patch == null || ctx.in.getPlayer().getPlantedTrees().contains(patch))
			&& (!inHouse || ctx.in.getPlayer().getHouse().hasAny(stage.getHouseObjects())) && !ctx.lacksItems(tile);
	}

	/** Places whose every allotment still grows a crop can't be planted in now: left out, unless every place is such. */
	private static List<WorldPoint> notGrowing(PlanContext ctx, List<WorldPoint> places)
	{
		Set<WorldPoint> growing = ctx.in.getPlayer().getGrowingAllotments();
		List<WorldPoint> free = places.stream().filter(place -> !growing.contains(place)).collect(toList());
		return free.isEmpty() ? places : free;
	}

	private static List<List<int[]>> permutations(List<int[]> items)
	{
		List<List<int[]>> orders = new ArrayList<>();
		permute(items, 0, orders);
		return orders;
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

	private static Route followed(PlanInput in, Searches searches, List<List<int[]>> orders, LabelSearch.Label bestGoal, Route best)
	{
		String identity = in.getFollowedIdentity();
		if (identity == null || best == null || identity.equals(best.getIdentity()))
		{
			return null;
		}
		int limit = bestGoal.rank + 2 * RoutePlanner.COMMIT_MARGIN_TICKS;
		LabelSearch search = searches.followed(orders, in.getFollowedFirstTransport(), identity, limit);
		return search == null ? null : RouteBuilder.build(searches.ctx, search.goal);
	}

	private static int probeRankLimitHalfTicks(PlanInput in)
	{
		int ticks = in.getProbeRankLimitTicks();
		return ticks >= Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : 2 * ticks + 1;
	}

	/** Fills {@code ranks} with each tile's ranking ticks, rounded up. */
	private static Map<WorldPoint, Integer> probeTicks(PlanContext ctx, LabelSearch search, Map<WorldPoint, Integer> ranks)
	{
		Map<WorldPoint, Integer> ticks = new LinkedHashMap<>();
		search.probeBest.forEach((node, goal) ->
		{
			Route pick = RouteBuilder.build(ctx, goal);
			WorldPoint tile = ctx.standTile(node);
			ticks.put(tile, pick.getTicks());
			ranks.put(tile, (pick.getRankHalfTicks() + 1) / 2);
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
				List<String> ways = waysNamed(need.getName());
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
			ways.addAll(Arrays.asList(name.split(",? or ")));
		}
		return ways;
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
		List<RequiredItems.Verdict> verdicts = new ArrayList<>(List.of(ctx.required));
		verdicts.addAll(ctx.stageVerdicts());
		List<String> notes = new ArrayList<>(missingList(verdicts));
		if (ctx.in.getRequired().isSpadeAtLeprechaun() && ctx.required.getMissing().stream().anyMatch(need -> need.getName().equalsIgnoreCase("spade")))
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
		Set<Integer> targets = orders.get(0).stream().flatMapToInt(Arrays::stream).boxed().collect(toSet());
		boolean noWalkData = targets.stream().anyMatch(n -> n == WalkTable.NO_NODE || !ctx.isTableNode(n) || table.reverseEnd(n) == table.reverseFrom(n));
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
	 * Mountain): only gates that items alone keep shut. Without the bank edge there are no banked gates, so the
	 * player's own gates are judged with the bank instead.
	 */
	private static List<List<String>> lockingItems(PlanContext ctx, Set<Integer> targets)
	{
		PlayerSnapshot judged = withBank(ctx);
		Set<List<String>> missing = new LinkedHashSet<>();
		shutWaysInto(ctx, ctx.gates[ctx.gates[PlanContext.BANKED] != null ? PlanContext.BANKED : PlanContext.NOT_BANKED], targets).flatMap(List::stream)
			.map(gate -> ctx.judge("gate", gate, judged))
			.filter(verdict -> !verdict.isAvailable() && verdict.getMissingItems().size() == verdict.getWhyNot().size())
			.forEach(verdict -> missing.addAll(verdict.getMissingItems()));
		return List.copyOf(missing);
	}

	/** Each way into the targets that {@code gates} shut, as the gates that shut it. */
	private static Stream<List<Requirements>> shutWaysInto(PlanContext ctx, WalkGates gates, Set<Integer> targets)
	{
		WalkTable table = ctx.table;
		return targets.stream().flatMap(target -> IntStream.range(table.reverseFrom(target), table.reverseEnd(target)).boxed()
			.flatMap(i -> gates.shutWays(table.reverseEntry(i)).stream()));
	}

	/** The player as a missing note judges them: with the bank, where it counts. */
	private static PlayerSnapshot withBank(PlanContext ctx)
	{
		return ctx.in.getBanked() != null ? ctx.in.getBanked() : ctx.in.getPlayer();
	}

	/** The way in the player lacks, from the nearest transport landing by a target (the Dragontooth boat's fare). */
	private static List<String> blockedRideNotes(PlanContext ctx)
	{
		PlayerSnapshot judged = withBank(ctx);
		List<WorldPoint> targets = ctx.in.getStep().getTargets();
		return ctx.in.getCatalog().getTeleports().stream()
			.filter(row -> row.getDestination() != null && distanceToNearest(row.getDestination(), targets) <= BLOCKED_RIDE_TILES)
			.sorted(Comparator.comparingInt(row -> distanceToNearest(row.getDestination(), targets)))
			.map(row -> ctx.judge(row, judged))
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
		needs.forEach(ways -> lines.addAll(alternativeLines(ways)));
		return lines;
	}

	/** Needs with one way each, all of them wanted. */
	private static List<List<String>> each(List<String> needs)
	{
		return needs.stream().map(List::of).collect(toList());
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
			lines.add("- " + wayLine(way));
		}
		return lines;
	}

	private static int distanceToNearest(WorldPoint tile, List<WorldPoint> targets)
	{
		return targets.stream().mapToInt(tile::distanceTo).min().orElse(Integer.MAX_VALUE);
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
		return shutWaysInto(ctx, ctx.gates[PlanContext.NOT_BANKED], targets).map(way -> unfinishedQuests(way, player))
			.filter(quests -> !quests.isEmpty()).collect(toCollection(TreeSet::new));
	}

	private static String unfinishedQuests(List<Requirements> gates, PlayerSnapshot player)
	{
		return gates.stream().flatMap(gate -> gate.getQuests().stream()).filter(quest -> !player.finished(quest))
			.map(Quest::getName).distinct().sorted().collect(joining(" and "));
	}

	@RequiredArgsConstructor
	private static final class Searches
	{
		final PlanContext ctx;
		final int area;
		final boolean probes;
		int labels;

		/** Each order after the first stops at the best rank so far. */
		LabelSearch bestOrder(List<List<int[]>> orders, int rankLimit)
		{
			LabelSearch best = null;
			for (List<int[]> order : orders)
			{
				LabelSearch search = run(order, Math.min(rankLimit, rankOf(best)));
				if (best == null || beats(search, best))
				{
					best = search;
				}
			}
			return best;
		}

		/** The best order's search of the routes that start with transport {@code first}; null when none is in the limit. */
		LabelSearch followed(List<List<int[]>> orders, String first, String identity, int rankLimit)
		{
			LabelSearch best = null;
			for (List<int[]> order : orders)
			{
				LabelSearch search = run(new LabelSearch(ctx, order, area, first,
					goal -> identity.equals(RouteBuilder.build(ctx, goal).getIdentity()), rankLimit));
				if (search.goal != null && beats(search, best))
				{
					best = search;
				}
			}
			return best;
		}

		/** A lower rank, or at the same rank the better tie: nothing used up, then no spellbook change, then no bank stop. */
		private static boolean beats(LabelSearch search, LabelSearch best)
		{
			if (search.goal == null || best == null || best.goal == null || search.goal.rank != best.goal.rank)
			{
				return rankOf(search) < rankOf(best);
			}
			return LabelSearch.TIE_ORDER.compare(search.goal, best.goal) < 0;
		}

		private static int rankOf(LabelSearch search)
		{
			return search == null || search.goal == null ? Integer.MAX_VALUE : search.goal.rank;
		}

		private LabelSearch run(List<int[]> order, int rankLimit)
		{
			return run(new LabelSearch(ctx, order, probes, area, rankLimit));
		}

		private LabelSearch run(LabelSearch search)
		{
			search.run();
			labels += search.labels;
			return search;
		}
	}

	/** A searched goal label as the route the guide follows: its legs, withdraw list and what it spends. */
	private static final class RouteBuilder
	{
		/** Null for no goal. The rank leaves out the penalties that only choose between routes. */
		static Route build(PlanContext ctx, LabelSearch.Label goal)
		{
			if (goal == null)
			{
				return null;
			}
			List<Leg> legs = new ArrayList<>();
			WorldPoint at = ctx.in.getStart().getTile();
			boolean stageReached = false;
			for (LabelSearch.Label label : goal.chain())
			{
				if (label.kind == null)
				{
					stageReached |= label.parent != null && label.leg != label.parent.leg;
					continue;
				}
				Leg leg = leg(ctx, label, at);
				Leg last = legs.isEmpty() ? null : legs.get(legs.size() - 1);
				boolean merge = leg.getKind() == Leg.Kind.WALK && last != null && last.getKind() == Leg.Kind.WALK
					&& !leavesByExit(ctx, last) && !stageReached;
				stageReached = false;
				if (merge)
				{
					legs.set(legs.size() - 1, Leg.walk(last.getFrom(), leg.getTo(), last.getHalfTicks() + leg.getHalfTicks(), leg.getBookAfter()));
				}
				else
				{
					legs.add(leg);
				}
				at = leg.getTo();
			}
			Map<String, Integer> spent = new LinkedHashMap<>();
			for (LabelSearch.Label label : goal.chain())
			{
				tally(ctx, label.ref, spent);
				tally(ctx, label.ref2, spent);
			}
			List<ItemUse> withdraw = withdraw(ctx, goal);
			boolean banked = goal.b == PlanContext.BANKED;
			boolean depositFirst = banked && slotsNeeded(ctx, withdraw) > ctx.in.getPlayer().getFreeInventorySlots();
			int extra = depositFirst ? PlanContext.DEPOSIT_FIRST_HALF_TICKS : 0;
			boolean bankLeg = legs.stream().anyMatch(leg -> leg.getKind() == Leg.Kind.BANK);
			return Route.builder()
				.legs(List.copyOf(legs))
				.halfTicks(goal.half + extra)
				.rankHalfTicks(goal.rank - goal.choicePenalty + extra)
				.banked(banked)
				.withdraw(withdraw)
				.carriedAfterBank(withdraw.stream().collect(toUnmodifiableMap(ItemUse::getItemId,
					use -> ctx.in.getPlayer().carried(use.getItemId()) + use.getQuantity(), Integer::sum)))
				.depositFirst(depositFirst)
				.openBankStart(banked && !bankLeg)
				.startBook(ctx.homeBook)
				.endBook(goal.endBook != null ? goal.endBook : LabelSearch.endBook(goal.k))
				.spent(spent)
				.build();
		}

		/** A start leg through an {@link Exit} stays a leg of its own, so the guide can name the exit. */
		private static boolean leavesByExit(PlanContext ctx, Leg leg)
		{
			PlanStart start = ctx.in.getStart();
			return start.getKind() == PlanStart.Kind.SCENE && leg.getFrom() != null && leg.getFrom().equals(start.getTile())
				&& start.getWalk().exitTo(leg.getTo()) != null;
		}

		private static List<ItemUse> withdraw(PlanContext ctx, LabelSearch.Label goal)
		{
			if (goal.b != PlanContext.BANKED)
			{
				return List.of();
			}
			Map<Integer, ItemUse> byItem = new LinkedHashMap<>(routeWithdraw(goal));
			ctx.required.getWithdraw().forEach(use -> byItem.putIfAbsent(use.getItemId(), use));
			for (int gateItem : goal.withdrawn)
			{
				byItem.putIfAbsent(gateItem, new ItemUse(Container.BANK, gateItem, 1));
			}
			return List.copyOf(byItem.values());
		}

		private static Map<Integer, ItemUse> routeWithdraw(LabelSearch.Label goal)
		{
			Map<Integer, ItemUse> byItem = new LinkedHashMap<>();
			for (LabelSearch.Label label : goal.chain())
			{
				for (TransportOptions.Edge ref : new TransportOptions.Edge[]{label.ref, label.ref2})
				{
					if (label.b == PlanContext.BANKED && ref != null)
					{
						ref.withdrawUses.forEach(use -> byItem.putIfAbsent(use.getItemId(), use));
					}
				}
			}
			return byItem;
		}

		/** A stack of something already in the inventory takes no new slot. */
		private static int slotsNeeded(PlanContext ctx, List<ItemUse> withdraw)
		{
			return (int) withdraw.stream().filter(use -> ctx.in.getPlayer().count(Container.INVENTORY, use.getItemId()) == 0).count();
		}

		/** Every unit a leg uses, tracked by the search or not. */
		private static void tally(PlanContext ctx, TransportOptions.Edge ref, Map<String, Integer> spent)
		{
			for (int i = 0; ref != null && i < ref.res.length; i++)
			{
				spent.merge(ctx.resources.name(ref.res[i]), ref.amounts[i], Integer::sum);
			}
		}

		private static Leg leg(PlanContext ctx, LabelSearch.Label label, WorldPoint at)
		{
			Book book = LabelSearch.bookOf(label.k);
			switch (label.kind)
			{
				case TELEPORT:
					return Leg.teleport(at, ((TransportOptions.Teleport) label.ref).row, label.edgeHalf, book);
				case SWAP_TELEPORT:
					return Leg.swapTeleport(at, ((TransportOptions.Teleport) label.ref).row, ((TransportOptions.Change) label.ref2).change, label.edgeHalf, book);
				case FAIRY_RING:
					return Leg.fairyRing(at, ((TransportOptions.Teleport) label.ref).row, ((TransportOptions.Teleport) label.ref2).row, label.edgeHalf, book);
				case SPELLBOOK_CHANGE:
					return Leg.spellbookChange(at, ((TransportOptions.Change) label.ref).change, label.edgeHalf, book);
				case BANK:
					return Leg.bank(at, label.edgeHalf, book);
				case WILDERNESS_EXIT:
					// The exit's node has no tile, so its origin class is WILD0's: its own class says how far south it goes.
					return Leg.wildernessExit(at, ctx.abstractClass(label.node).getExitY(), label.edgeHalf, book);
				case WALK:
					return Leg.walk(at, ctx.tile(label.node), label.edgeHalf, book);
				default:
					throw new IllegalArgumentException("unhandled leg kind " + label.kind);
			}
		}
	}
}
