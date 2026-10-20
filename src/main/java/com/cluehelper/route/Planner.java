package com.cluehelper.route;

import com.cluehelper.player.Book;
import com.cluehelper.player.Container;
import com.cluehelper.player.ItemUse;
import com.cluehelper.step.ClueTypes;
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

/**
 * Plans one step (PLAN §5.4): pure, so it runs on the executor with snapshot data only.
 * <p>
 * One forward label search gives each (banked, change category)'s best route; the bottom-up margin rule
 * picks Best among them, and Direct and Same book come from the same search. Alternatives with new first
 * transports come from searches that leave the earlier routes' first transports out.
 */
public final class Planner
{
	/** The margin a route with a bank stop or a walked change must beat its reference by (PLAN §5.4). */
	static final int MARGIN_MIN_HALF_TICKS = 10;
	static final int MARGIN_PERCENT = 10;
	private static final int REGION_SIZE = 64;
	/** Alternatives are shown only within Best's rank plus this, or plus Best's own rank if that is more. */
	static final int ALTERNATIVE_SLACK_HALF_TICKS = 60;
	/** Landings this close count as the same place: catalog landings spread 0–2 tiles (B09). */
	private static final int SAME_LANDING_TILES = 2;
	/** Rejected repeats and round trips each cost a search; past this many the plan shows what it has. */
	static final int MOST_ALTERNATIVE_SEARCHES = 12;

	private Planner()
	{
	}

	public static Plan plan(PlanInput in)
	{
		long started = System.nanoTime();
		Step step = in.getStep();
		if (step == null || step.getTargets() == null || step.getTargets().isEmpty())
		{
			return Plan.builder().stepKey(step == null ? null : step.getKey()).alternatives(List.of()).candidates(List.of())
				.probeTicks(Map.of()).why(List.of()).note(step == null ? "no step" : "no target tile for this step yet").build();
		}
		PlanContext ctx = new PlanContext(in);
		List<List<int[]>> orders = legOrders(ctx, step);
		ctx.freezeNodes();
		long contextNanos = System.nanoTime() - started;
		int area = step.getKind() == TargetKind.AREA ? areaHalfTicks(step) : 0;
		boolean probes = step.getKind() == TargetKind.PROBES;
		Searches searches = new Searches(ctx, area, probes);

		LabelSearch first = searches.bestOrder(orders, Set.of(), Integer.MAX_VALUE);
		Plan.PlanBuilder plan = Plan.builder().stepKey(step.getKey());
		Picks picks = new Picks(ctx, first);
		plan.best(picks.best).direct(picks.direct != picks.best ? picks.direct : null)
			.sameBook(picks.best != null && picks.best.isChanged() ? picks.sameBook : null)
			.fasterUnqualified(picks.fasterUnqualified)
			.candidates(picks.candidates);
		plan.probeTicks(probes ? probeTicks(ctx, first) : Map.of());
		plan.alternatives(probes || orders.size() > 1 ? List.of() : alternatives(in, searches, orders, picks));
		plan.why(probes ? List.of() : WhyList.of(ctx, orders.get(0).get(0)));
		plan.note(picks.best == null ? noRouteNote(ctx, orders) : ctx.targetInWilderness ? "Wilderness target" : null);
		plan.requirementNotes(requirementNotes(ctx));
		return plan.searchNanos(System.nanoTime() - started).contextNanos(contextNanos).labels(searches.labels)
			.searches(searches.count).build();
	}

	/** Each leg's goal nodes, per order to try: three-step tries all 6 orders, other sequences go in turn. */
	private static List<List<int[]>> legOrders(PlanContext ctx, Step step)
	{
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

	/** Half the area's radius, walked: (radius ÷ 2) tiles, which is radius ÷ 2 half-ticks (PLAN §5.4 AREA). */
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

	private static List<Route> alternatives(PlanInput in, Searches searches, List<List<int[]>> orders, Picks picks)
	{
		List<Route> alternatives = new ArrayList<>();
		Set<String> excluded = new HashSet<>();
		Set<String> identities = new HashSet<>();
		List<Route> shown = new ArrayList<>();
		Stream.of(picks.best, picks.direct, picks.sameBook).filter(Objects::nonNull).forEach(route ->
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
	 * Comes back to where it already was: leaving the shown routes' transports out can leave a round trip as the
	 * cheapest route with a transport ("walk to ring BIP, ring to DIP, ring back to BIP, walk 35").
	 */
	static boolean loops(Route route)
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

	/** Where the route's first transport lands; null for a walk. */
	private static WorldPoint firstLanding(Route route)
	{
		Leg first = route.getFirstTransport();
		return first == null ? null : first.getTo();
	}

	/**
	 * Lands beside a shown route and saves nothing it spends: Fishing cape and Max cape to Otto's Grotto are one
	 * alternative, while a free home teleport beside a paid spell stays.
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

	private static Map<WorldPoint, Integer> probeTicks(PlanContext ctx, LabelSearch search)
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
			}
		});
		return ticks;
	}

	/**
	 * Required items no source meets are listed, and the best route is still shown (PLAN §5.4). An unknown STASH never
	 * forces a bank stop silently: the note says a filled one would save it.
	 */
	static List<String> requirementNotes(PlanContext ctx)
	{
		RequiredItems required = ctx.in.getRequired();
		List<String> notes = new ArrayList<>();
		if (!ctx.required.getMissing().isEmpty())
		{
			notes.add("Not found on you or in your bank: " + ctx.required.getMissing().stream().map(RequiredItems.Need::getName)
				.collect(Collectors.joining(", ")));
		}
		boolean stepItemsFromBank = !ctx.required.isCarried() && ctx.required.isBanked()
			&& required.getNeeds().stream().anyMatch(RequiredItems.Need::isStepItem);
		if (required.getStash() == RequiredItems.Stash.UNKNOWN && (stepItemsFromBank || !ctx.required.getMissing().isEmpty()))
		{
			notes.add("STASH unknown — search it once. If it's filled, skip the bank");
		}
		else if (required.getStash() == RequiredItems.Stash.FILLED)
		{
			notes.add("Emote items are in the STASH");
		}
		if (required.isSpadeAtLeprechaun() && ctx.required.getMissing().stream().anyMatch(need -> need.getName().equalsIgnoreCase("spade")))
		{
			notes.add("Your spade is with a tool leprechaun");
		}
		return notes;
	}

	/** PLAN 5.7: no route because no node walks to the target within 200 tiles (and it isn't in the scene). */
	private static String noRouteNote(PlanContext ctx, List<List<int[]>> orders)
	{
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

	/** Runs the label searches of one plan, keeping their totals. */
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

		/** For a sequence with several orders, the order whose Best is lowest. */
		LabelSearch bestOrder(List<List<int[]>> orders, Set<String> excluded, int rankLimit)
		{
			LabelSearch best = null;
			int bestRank = Integer.MAX_VALUE;
			for (List<int[]> order : orders)
			{
				LabelSearch search = new LabelSearch(ctx, order, probes, area, excluded, rankLimit);
				search.run();
				labels += search.labels;
				count++;
				Route pick = new Picks(ctx, search).best;
				int rank = pick == null ? Integer.MAX_VALUE : pick.getRankHalfTicks();
				if (best == null || rank < bestRank)
				{
					best = search;
					bestRank = rank;
				}
			}
			return best;
		}
	}

	/** PLAN §5.4's bottom-up margin rule over one search's category bests, plus Direct and Same book. */
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

		/** Beats the reference by more than max(5 ticks, 10%); anything beats no reference. */
		static boolean qualifies(Route route, Route reference)
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

	/** Builds the route a goal label stands for. */
	static final class RouteBuilder
	{
		private RouteBuilder()
		{
		}

		static Route build(PlanContext ctx, LabelSearch.Label goal, int rank)
		{
			List<Leg> legs = new ArrayList<>();
			WorldPoint at = ctx.in.getStart().getTile();
			boolean wilderness = false;
			for (LabelSearch.Label label : goal.chain())
			{
				if (label.kind == null)
				{
					continue;
				}
				Leg leg = leg(ctx, label, at);
				Leg last = legs.isEmpty() ? null : legs.get(legs.size() - 1);
				if (leg.getKind() == Leg.Kind.WALK && last != null && last.getKind() == Leg.Kind.WALK)
				{
					legs.set(legs.size() - 1, new Leg(Leg.Kind.WALK, last.getFrom(), leg.getTo(), last.getHalfTicks() + leg.getHalfTicks(),
						null, null, null, leg.getBookAfter()));
				}
				else
				{
					legs.add(leg);
				}
				at = leg.getTo();
				wilderness |= at != null && OriginClass.of(at, ctx.in.getNoTeleport()).isWilderness();
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
				.rankHalfTicks(rank + extra)
				.banked(banked)
				.withdraw(withdraw)
				.withdrawInCoreTag(inCoreTag(ctx, goal, withdraw))
				.depositFirst(depositFirst)
				.pinIncluded(bankLeg && ctx.in.isPinPending())
				.openBankStart(banked && !bankLeg)
				.changed(goal.c != LabelSearch.NONE)
				.walkedChange(goal.c == LabelSearch.WALKED)
				.startBook(ctx.homeBook)
				.endBook(goal.endBook != null ? goal.endBook : LabelSearch.endBook(goal.k))
				.spent(spent)
				.confidence(goal.confidence())
				.wilderness(wilderness)
				.build();
		}

		/** The route's own bank items, then the step's required ones; one entry per item. */
		private static List<ItemUse> withdraw(PlanContext ctx, LabelSearch.Label goal)
		{
			if (goal.b != PlanContext.BANKED)
			{
				return List.of();
			}
			Map<Integer, ItemUse> byItem = new LinkedHashMap<>(routeWithdraw(goal));
			ctx.required.getWithdraw().forEach(use -> byItem.putIfAbsent(use.getItemId(), use));
			return List.copyOf(byItem.values());
		}

		private static Map<Integer, ItemUse> routeWithdraw(LabelSearch.Label goal)
		{
			Map<Integer, ItemUse> byItem = new LinkedHashMap<>();
			for (LabelSearch.Label label : goal.chain())
			{
				for (Object ref : new Object[]{label.ref, label.ref2})
				{
					List<ItemUse> uses = ref instanceof PlanContext.Teleport ? ((PlanContext.Teleport) ref).withdrawUses
						: ref instanceof PlanContext.Change ? ((PlanContext.Change) ref).withdrawUses : null;
					if (label.b == PlanContext.BANKED && uses != null)
					{
						uses.forEach(use -> byItem.putIfAbsent(use.getItemId(), use));
					}
				}
			}
			return byItem;
		}

		/** Required items whose every need is one core's *clue* tag covers; the route's own items never are. */
		private static Set<Integer> inCoreTag(PlanContext ctx, LabelSearch.Label goal, List<ItemUse> withdraw)
		{
			Set<Integer> routeItems = routeWithdraw(goal).keySet();
			Set<Integer> tagged = new HashSet<>();
			for (ItemUse use : withdraw)
			{
				List<RequiredItems.Need> helped = ctx.in.getRequired().getNeeds().stream()
					.filter(need -> need.getRequirement().fulfilledBy(use.getItemId())).collect(Collectors.toList());
				if (!routeItems.contains(use.getItemId()) && !helped.isEmpty() && helped.stream().allMatch(RequiredItems.Need::isInCoreTag))
				{
					tagged.add(use.getItemId());
				}
			}
			return Set.copyOf(tagged);
		}

		/** A stack of something already in the inventory takes no new slot. */
		private static int slotsNeeded(PlanContext ctx, List<ItemUse> withdraw)
		{
			return (int) withdraw.stream().filter(use -> ctx.in.getPlayer().count(Container.INVENTORY, use.getItemId()) == 0).count();
		}

		/** Every unit a leg uses, tracked by the search or not, for the route's spent list. */
		private static void tally(PlanContext ctx, Object ref, Map<String, Integer> spent)
		{
			int[] res = ref instanceof PlanContext.Teleport ? ((PlanContext.Teleport) ref).res
				: ref instanceof PlanContext.Change ? ((PlanContext.Change) ref).res : null;
			int[] amounts = ref instanceof PlanContext.Teleport ? ((PlanContext.Teleport) ref).amounts
				: ref instanceof PlanContext.Change ? ((PlanContext.Change) ref).amounts : null;
			for (int i = 0; res != null && i < res.length; i++)
			{
				spent.merge(ctx.resources.name(res[i]), amounts[i], Integer::sum);
			}
		}

		private static Leg leg(PlanContext ctx, LabelSearch.Label label, WorldPoint at)
		{
			Book book = LabelSearch.bookOf(label.k);
			switch (label.kind)
			{
				case TELEPORT:
				case SWAP_TELEPORT:
				{
					PlanContext.Teleport tele = (PlanContext.Teleport) label.ref;
					PlanContext.Change swap = (PlanContext.Change) label.ref2;
					return new Leg(label.kind, at, tele.row.getDestination(), label.edgeHalf, tele.row, null,
						swap == null ? null : swap.change, book);
				}
				case FAIRY_RING:
				{
					PlanContext.Teleport entry = (PlanContext.Teleport) label.ref;
					PlanContext.Teleport landing = (PlanContext.Teleport) label.ref2;
					return new Leg(label.kind, at, landing.row.getDestination(), label.edgeHalf, entry.row, landing.row, null, book);
				}
				case SPELLBOOK_CHANGE:
					return new Leg(label.kind, at, at, label.edgeHalf, null, null, ((PlanContext.Change) label.ref).change, book);
				case BANK:
					return new Leg(label.kind, at, at, label.edgeHalf, null, null, null, book);
				case WILDERNESS_EXIT:
					return new Leg(label.kind, at, new WorldPoint(at.getX(), at.getY() - label.edgeHalf, at.getPlane()), label.edgeHalf,
						null, null, null, book);
				default:
					return new Leg(Leg.Kind.WALK, at, ctx.tile(label.node), label.edgeHalf, null, null, null, book);
			}
		}
	}
}
