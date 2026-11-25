package com.cluehelper.route;

import com.cluehelper.SpellbookChanges;
import com.cluehelper.player.Book;
import java.util.*;
import java.util.function.Predicate;
import lombok.AccessLevel;
import lombok.experimental.*;
import net.runelite.api.coords.WorldPoint;

/**
 * Label-setting Dijkstra over states (leg, node, banked b, book k, has-transport t).
 * <p>
 * Spellbook Swap is fused into the spell edge it enables. A pending revert is a book of its own that turns into Lunar
 * on its free cast, or once its window passes.
 */
final class LabelSearch
{
	/**
	 * Ranked onto a bank stop and a walked change, so either is taken only when it beats the route without it by more
	 * than 2 ticks: near-ties stay on fewer steps.
	 */
	static final int MARGIN_HALF_TICKS = 5;
	/** A carried spellbook change is taken only when strictly faster: on a tie the route stays on its book. */
	private static final int CHANGE_TIE_HALF_TICKS = 1;
	private static final int BOOKS = 7;
	private static final int FIRST_PENDING = 4;
	private static final int LUNAR = Book.LUNAR.ordinal();
	private static final int MAX_LABELS = 4;
	private static final int STATES_PER_NODE = 2 * BOOKS * 2;
	private static final int SURFACE_WILDERNESS_MAX_Y = 3972;
	private static final int[] NOTHING = new int[0];
	private static final int CANNOT_CAST = -1;

	final PlanContext ctx;
	final List<int[]> legTargets;
	final boolean probes;
	final int areaHalfTicks;
	final int nodes;
	final int revertHalfTicks;
	/** Only routes whose first transport has this key, a way into the house aside; null for any. */
	final String onlyFirst;
	/** Whether a label at the goal is the route searched for; any is, but on the followed route's search. */
	final Predicate<Label> wantedGoal;
	final boolean requireTransport;
	final int rankLimit;
	final Label[] slots;
	final LowerBound[] bounds;
	final boolean[][] targets;
	/**
	 * An exact tie goes to the route that uses nothing up, then to the one without a bank stop; both only grow along a
	 * route, so a partial route that would win the tie is expanded first. After that the label with more real time carries
	 * fewer ranking-only penalties (teleport ties, bank margins): a walk over two teleports a tick each faster.
	 */
	static final Comparator<Label> TIE_ORDER = Comparator.<Label>comparingInt(label -> label.fee).thenComparingInt(label -> label.b);
	final PriorityQueue<Label> queue = new PriorityQueue<>(Comparator.<Label>comparingInt(label -> label.f).thenComparing(TIE_ORDER)
		.thenComparing(Comparator.<Label>comparingInt(label -> label.half).reversed()));

	@PackagePrivate
	Label goal;
	@PackagePrivate
	final Map<Integer, Label> probeBest = new HashMap<>();
	@PackagePrivate
	int labels;

	LabelSearch(PlanContext ctx, List<int[]> legTargets, boolean probes, int areaHalfTicks, int rankLimit)
	{
		this(ctx, legTargets, probes, areaHalfTicks, null, goal -> true, rankLimit);
	}

	/**
	 * The followed route again: the best route that starts with transport {@code onlyFirst} ({@link Route#WALK} for none)
	 * and whose goal label is {@code wantedGoal}, up to {@code rankLimit}. A bank stop is a state of its own, so the route
	 * with it and the one without both reach the goal.
	 */
	LabelSearch(PlanContext ctx, List<int[]> legTargets, int areaHalfTicks, String onlyFirst, Predicate<Label> wantedGoal,
		int rankLimit)
	{
		this(ctx, legTargets, false, areaHalfTicks, onlyFirst, wantedGoal, rankLimit);
	}

	private LabelSearch(PlanContext ctx, List<int[]> legTargets, boolean probes, int areaHalfTicks, String onlyFirst,
		Predicate<Label> wantedGoal, int rankLimit)
	{
		this.rankLimit = rankLimit;
		this.ctx = ctx;
		this.legTargets = legTargets;
		this.probes = probes;
		this.areaHalfTicks = areaHalfTicks;
		this.onlyFirst = onlyFirst;
		this.wantedGoal = wantedGoal;
		this.requireTransport = onlyFirst != null && !Route.WALK.equals(onlyFirst);
		this.nodes = ctx.nodeCount();
		this.revertHalfTicks = ctx.startPending ? 2 * ctx.in.getRevertTicksLeft() : -1;
		this.slots = new Label[legTargets.size() * nodes * STATES_PER_NODE * MAX_LABELS];
		this.bounds = new LowerBound[legTargets.size()];
		this.targets = new boolean[legTargets.size()][nodes];
		for (int leg = 0; leg < bounds.length; leg++)
		{
			bounds[leg] = probes ? null : ctx.lowerBound(legTargets.get(leg));
			for (int target : legTargets.get(leg))
			{
				targets[leg][target] = true;
			}
		}
	}

	void run()
	{
		offer(null, startNode(), startBankState(), startBook(), 0, 0, 0, 0, null, null, null, new int[0], NOTHING);
		while (!queue.isEmpty())
		{
			Label label = queue.poll();
			if (label.dead)
			{
				continue;
			}
			if (label.f > rankLimit)
			{
				break;
			}
			if (label.node == ctx.goal)
			{
				if (wantedGoal.test(label))
				{
					goal = label;
					break;
				}
				continue;
			}
			expand(label);
		}
	}

	private int startNode()
	{
		PlanStart start = ctx.in.getStart();
		return start.getKind() == PlanStart.Kind.AT_NODE ? start.getNode() : ctx.start;
	}

	private int startBankState()
	{
		return ctx.in.isAtOpenBank() && ctx.options[PlanContext.BANKED] != null ? PlanContext.BANKED : PlanContext.NOT_BANKED;
	}

	private int startBook()
	{
		return ctx.startPending ? pendingIndex(ctx.startBook) : ctx.startBook.ordinal();
	}

	private void expand(Label from)
	{
		int k = effectiveBook(from.k, from.half);
		OriginClass abstractClass = ctx.abstractClass(from.node);
		if (abstractClass != null)
		{
			expandTeleports(from, abstractClass, k);
			expandCape(from, abstractClass, k);
			return;
		}
		if (targets[from.leg][from.node])
		{
			arrive(from, k);
		}
		expandWalks(from, k);
		OriginClass origin = ctx.originClass(from.node);
		if (origin.allowsTeleports())
		{
			move(from, ctx.abstractNode(origin), k, 0, null, from.withdrawn);
		}
		expandExits(from, k);
		if (ctx.houseUsable(from.node, from.t))
		{
			expandLocatedTeleports(from, k);
			expandFairyRing(from, k);
			expandLocatedChanges(from, origin, k);
		}
		expandBank(from, k);
	}

	private void expandLocatedTeleports(Label from, int k)
	{
		List<TransportOptions.Teleport> here = ctx.options[from.b].located.get(from.node);
		if (here == null)
		{
			return;
		}
		for (TransportOptions.Teleport tele : here)
		{
			if (tele.node != from.node && !ctx.forbidden(tele.node))
			{
				transport(from, tele.node, k, tele.halfTicks, Leg.Kind.TELEPORT, tele, null);
			}
		}
	}

	private void arrive(Label from, int k)
	{
		boolean lastLeg = from.leg + 1 >= legTargets.size();
		Book needed = ctx.stageBook(from.node) == null && lastLeg ? ctx.anywhereBook() : ctx.stageBook(from.node);
		if (ctx.stageNeedsBank(from.node, from.b) || needed != null && bookOf(k) != needed)
		{
			return;
		}
		int[] staged = sortedUnion(from.withdrawn, ctx.stageWithdraw(from.node, from.b));
		if (!lastLeg)
		{
			int half = withdrawHalfTicks(from, staged);
			offer(from, from.node, from.b, k, from.t, from.leg + 1, half, half, null, null, null, from.spent, staged);
			return;
		}
		if (from.b == PlanContext.NOT_BANKED && !ctx.arrivesWithoutBank(from.node))
		{
			return;
		}
		int[] withdrawn = from.b == PlanContext.BANKED ? sortedUnion(staged, ctx.requiredWithdraw) : staged;
		int area = areaHalfTicks + withdrawHalfTicks(from, withdrawn);
		Book end = endBook(k);
		int strand = ctx.strandHalfTicks(end, from.b, capeUses(from.spent));
		if (probes)
		{
			Label current = probeBest.get(from.node);
			if (current == null || current.rank > from.rank + area + strand)
			{
				probeBest.put(from.node, goalLabel(from, end, area, strand, withdrawn));
			}
			return;
		}
		if (!(requireTransport && from.t == 0))
		{
			Label goal = offer(from, ctx.goal, from.b, 0, 0, from.leg, area, area + strand, null, null, null, from.spent, withdrawn);
			if (goal != null)
			{
				goal.endBook = end;
			}
		}
	}

	private Label goalLabel(Label from, Book end, int area, int strand, int[] withdrawn)
	{
		Label goal = new Label();
		goal.parent = from;
		goal.node = ctx.goal;
		goal.b = from.b;
		goal.half = from.half + area;
		goal.rank = from.rank + area + strand;
		goal.choicePenalty = from.choicePenalty;
		goal.fee = from.fee;
		goal.spent = from.spent;
		goal.withdrawn = withdrawn;
		goal.endBook = end;
		return goal;
	}

	private void expandWalks(Label from, int k)
	{
		if (from.node == ctx.start)
		{
			expandStartLegs(from, k);
			return;
		}
		if (!ctx.isTableNode(from.node))
		{
			return;
		}
		WalkTable table = ctx.table;
		WalkGates gates = ctx.gates[from.b];
		for (int e = table.entriesFrom(from.node); e < table.entriesEnd(from.node); e++)
		{
			int cost = gates.cost(e);
			int to = table.entryTo(e);
			if (cost == WalkTable.NO_PATH || ctx.forbidden(to))
			{
				continue;
			}
			int[] withdrawn = sortedUnion(from.withdrawn, gates.withdraw(e));
			if (!pointlessAfterBank(from, withdrawn) || ctx.usesBankItems(to))
			{
				move(from, to, k, Running.halfTicks(cost) + withdrawHalfTicks(from, withdrawn), Leg.Kind.WALK, withdrawn);
			}
		}
	}

	private void expandStartLegs(Label from, int k)
	{
		PlanStart start = ctx.in.getStart();
		if (start.getKind() != PlanStart.Kind.SCENE)
		{
			return;
		}
		WalkCosts walk = start.getWalk();
		WalkTable table = ctx.table;
		for (int node = 0; node < table.nodeCount(); node++)
		{
			int cost = walk.cost(table.placed(node));
			if (cost != WalkCosts.UNREACHED && !ctx.forbidden(node))
			{
				move(from, node, k, Running.halfTicks(cost), Leg.Kind.WALK, from.withdrawn);
			}
		}
		for (int[] targets : legTargets)
		{
			for (int node : targets)
			{
				int cost = ctx.isTableNode(node) ? WalkCosts.UNREACHED : walk.cost(ctx.tile(node));
				if (cost != WalkCosts.UNREACHED)
				{
					move(from, node, k, Running.halfTicks(cost), Leg.Kind.WALK, from.withdrawn);
				}
			}
		}
	}

	private void expandExits(Label from, int k)
	{
		WorldPoint tile = ctx.tile(from.node);
		OriginClass origin = ctx.originClass(from.node);
		if (tile == null || !origin.isWilderness() || tile.getY() > SURFACE_WILDERNESS_MAX_Y)
		{
			return;
		}
		exit(from, k, tile, origin, OriginClass.WILD30);
		exit(from, k, tile, origin, OriginClass.WILD20);
		exit(from, k, tile, origin, OriginClass.WILD0);
	}

	private void exit(Label from, int k, WorldPoint tile, OriginClass origin, OriginClass to)
	{
		if (to.ordinal() < origin.ordinal() && tile.getY() > to.getExitY())
		{
			move(from, ctx.abstractNode(to), k, Running.halfTicks(tile.getY() - to.getExitY()), Leg.Kind.WILDERNESS_EXIT, from.withdrawn);
		}
	}

	private void expandTeleports(Label from, OriginClass origin, int k)
	{
		TransportOptions options = ctx.options[from.b];
		TransportOptions.Change swap = ctx.in.getSettings().getSpellbookChanges() != SpellbookChanges.OFF ? options.swap : null;
		for (TransportOptions.Teleport tele : options.teleports)
		{
			if (!origin.allows(tele.maxWilderness) || origin == OriginClass.POH && tele.minigame || ctx.forbidden(tele.node))
			{
				continue;
			}
			int bookAfter = bookAfterCasting(tele.book, k);
			if (bookAfter != CANNOT_CAST)
			{
				transport(from, tele.node, bookAfter, tele.halfTicks, Leg.Kind.TELEPORT, tele, null);
			}
			if (swap != null && k == LUNAR && tele.book != null && tele.book != Book.LUNAR)
			{
				transport(from, tele.node, LUNAR, tele.halfTicks + swap.halfTicks, Leg.Kind.SWAP_TELEPORT, tele, swap);
			}
		}
	}

	private static int bookAfterCasting(Book rowBook, int k)
	{
		if (rowBook == null)
		{
			return k;
		}
		if (k < FIRST_PENDING)
		{
			return rowBook.ordinal() == k ? k : CANNOT_CAST;
		}
		return rowBook == pendingBook(k) ? LUNAR : CANNOT_CAST;
	}

	/** The Magic or Max cape changes to any expanded book wherever teleports work, which also ends a pending revert. */
	private void expandCape(Label from, OriginClass origin, int k)
	{
		TransportOptions.Change cape = ctx.options[from.b].cape;
		if (cape == null || !ctx.changesAllowed(origin))
		{
			return;
		}
		for (Book book : ctx.expanded)
		{
			if (k >= FIRST_PENDING || book.ordinal() != k)
			{
				change(from, from.node, book.ordinal(), cape, CHANGE_TIE_HALF_TICKS);
			}
		}
	}

	/**
	 * A single altar or Tyss toggles its book, or goes back to Standard when already on it; the occult altar and a
	 * mounted cape go to any expanded book.
	 */
	private void expandLocatedChanges(Label from, OriginClass origin, int k)
	{
		List<TransportOptions.Change> here = ctx.options[from.b].changes.get(from.node);
		if (here == null || !ctx.changesAllowed(origin))
		{
			return;
		}
		Book current = bookOf(k);
		for (TransportOptions.Change change : here)
		{
			Book toggles = change.change.getToggles();
			for (Book next : toggles == null ? ctx.expanded : Set.of(current == toggles ? Book.STANDARD : toggles))
			{
				if (ctx.expanded.contains(next) && (k >= FIRST_PENDING || next != current))
				{
					change(from, from.node, next.ordinal(), change, MARGIN_HALF_TICKS);
				}
			}
		}
	}

	private void expandFairyRing(Label from, int k)
	{
		TransportOptions options = ctx.options[from.b];
		TransportOptions.Teleport entry = options.fairyEntries.get(from.node);
		if (entry == null)
		{
			return;
		}
		for (TransportOptions.Teleport landing : options.fairyLandings)
		{
			if (landing.node != from.node && !ctx.forbidden(landing.node))
			{
				int half = Math.max(entry.halfTicks, landing.halfTicks) - (ctx.isLastFairyRing(landing.row) ? 2 * landing.row.getMenuTicks() : 0);
				transport(from, landing.node, k, half, Leg.Kind.FAIRY_RING, entry, landing);
			}
		}
	}

	/**
	 * The only way into the banked state. Withdrawals are charged on the edges that use them, since the withdraw list
	 * is only known edge by edge.
	 */
	private void expandBank(Label from, int k)
	{
		if (from.b == PlanContext.NOT_BANKED && ctx.options[PlanContext.BANKED] != null && ctx.isUsableBank(from.node))
		{
			bankStop(from, k, ctx.bankStopHalfTicks, from.withdrawn);
			takeGateItems(from, k, ctx.bankStopHalfTicks);
		}
	}

	/**
	 * A web's knife or sword, the Kharazi Jungle's axe and machete, a ride's items, or a stage's gear, is used a few legs
	 * after the bank, where a stop that took nothing yet only walks to bank-item uses; so another stop takes them at once,
	 * one ride's items or one gate's tools each, so a web's knife never brings the jungle's axe along.
	 */
	private void takeGateItems(Label from, int k, int bankHalfTicks)
	{
		takeAtStop(from, k, bankHalfTicks, new int[0]);
		for (int[] items : itemsOfRidesTowardTarget(from))
		{
			takeAtStop(from, k, bankHalfTicks, items);
		}
		for (int[] tools : ctx.gates[PlanContext.BANKED].storedGateTools())
		{
			takeAtStop(from, k, bankHalfTicks, tools);
		}
	}

	/**
	 * The stored items of each ride that lands nearer the leg's target than the bank, once per distinct set. They come
	 * before the gates' tools: a stop keeps only {@link #MAX_LABELS} labels, and a ride's use is surer than a web's.
	 */
	private Collection<int[]> itemsOfRidesTowardTarget(Label bank)
	{
		LowerBound bound = bounds[bank.leg];
		Map<String, int[]> items = new LinkedHashMap<>();
		for (TransportOptions.Teleport ride : ctx.storedItemRides())
		{
			if (bound == null || ride.node != WalkTable.NO_NODE && bound.of(ride.node) < bound.of(bank.node))
			{
				items.putIfAbsent(Arrays.toString(ride.withdraw), ride.withdraw);
			}
		}
		return items.values();
	}

	private void takeAtStop(Label from, int k, int bankHalfTicks, int[] tools)
	{
		int[] withdrawn = sortedUnion(sortedUnion(from.withdrawn, tools), ctx.stageWithdraw());
		if (withdrawn.length > from.withdrawn.length)
		{
			bankStop(from, k, bankHalfTicks + withdrawHalfTicks(from, withdrawn), withdrawn);
		}
	}

	private void bankStop(Label from, int k, int half, int[] withdrawn)
	{
		penalise(offer(from, from.node, PlanContext.BANKED, k, from.t, from.leg, half, half + MARGIN_HALF_TICKS, Leg.Kind.BANK, null, null,
			from.spent, withdrawn), MARGIN_HALF_TICKS);
	}

	private static void penalise(Label label, int choiceHalfTicks)
	{
		if (label != null)
		{
			label.choicePenalty += choiceHalfTicks;
		}
	}

	private static void charge(Label label, int priceHalfTicks)
	{
		if (label != null)
		{
			label.fee += priceHalfTicks;
		}
	}

	private void transport(Label from, int node, int k, int half, Leg.Kind kind, TransportOptions.Teleport tele,
		TransportOptions.Edge second)
	{
		int t = 1;
		if (onlyFirst != null && from.t == 0)
		{
			String key = kind == Leg.Kind.FAIRY_RING ? Route.fairyKey(((TransportOptions.Teleport) second).row) : Route.teleportKey(tele.row);
			boolean first = key.equals(onlyFirst);
			if (Route.WALK.equals(onlyFirst) || !first && node != ctx.pohNode)
			{
				return;
			}
			t = first ? 1 : 0;
		}
		int[] spent = spend(from.spent, tele.res, tele.amounts, from.b);
		int[] withdrawn = sortedUnion(from.withdrawn, tele.withdraw);
		if (second != null)
		{
			spent = spend(spent, second.res, second.amounts, from.b);
			withdrawn = sortedUnion(withdrawn, second.withdraw);
		}
		if (spent != null && (!pointlessAfterBank(from, withdrawn) || ctx.usesBankItems(node)))
		{
			int total = half + withdrawHalfTicks(from, withdrawn);
			int choice = kind == Leg.Kind.SWAP_TELEPORT ? CHANGE_TIE_HALF_TICKS : 0;
			int price = tele.priceHalfTicks + (second == null ? 0 : second.priceHalfTicks);
			Label label = offer(from, node, from.b, k, t, from.leg, total, total + PlanContext.TELEPORT_TIE_HALF_TICKS + choice + price, kind,
				tele, second, spent, withdrawn);
			penalise(label, choice);
			charge(label, price);
		}
	}

	private void change(Label from, int node, int k, TransportOptions.Change change, int choiceHalfTicks)
	{
		int[] spent = spend(from.spent, change.res, change.amounts, from.b);
		int[] withdrawn = sortedUnion(from.withdrawn, change.withdraw);
		if (spent != null && !pointlessAfterBank(from, withdrawn))
		{
			int total = change.halfTicks + withdrawHalfTicks(from, withdrawn);
			int penalty = change.priceHalfTicks + choiceHalfTicks;
			Label label = offer(from, node, from.b, k, from.t, from.leg, total, total + penalty, Leg.Kind.SPELLBOOK_CHANGE,
				change, null, spent, withdrawn);
			penalise(label, choiceHalfTicks);
			charge(label, change.priceHalfTicks);
		}
	}

	/** The same route without the bank stop would be faster. */
	private boolean pointlessAfterBank(Label from, int[] withdrawn)
	{
		return from.b == PlanContext.BANKED && withdrawn.length == 0 && bankStopsArePruned();
	}

	private boolean bankStopsArePruned()
	{
		return ctx.arrivesWithoutBank() && !ctx.in.isAtOpenBank();
	}

	private static int[] sortedUnion(int[] a, int[] b)
	{
		if (b.length == 0)
		{
			return a;
		}
		if (a.length == 0)
		{
			return b;
		}
		int[] out = new int[a.length + b.length];
		int i = 0;
		int j = 0;
		int n = 0;
		while (i < a.length || j < b.length)
		{
			int next = j >= b.length || i < a.length && a[i] <= b[j] ? a[i] : b[j];
			if (i < a.length && a[i] == next)
			{
				i++;
			}
			if (j < b.length && b[j] == next)
			{
				j++;
			}
			out[n++] = next;
		}
		return n == out.length ? out : Arrays.copyOf(out, n);
	}

	/**
	 * Withdrawing later costs the same as now, except after a bank stop that took nothing: that one only walks to
	 * bank-item uses, so it can't stand in for a stop that took something.
	 */
	private boolean dominates(int bankState, int aRank, int aHalf, int[] aSpent, int[] aWithdrawn, int bRank, int bHalf, int[] bSpent,
		int[] bWithdrawn)
	{
		if (bankState == PlanContext.BANKED && aWithdrawn.length == 0 && bWithdrawn.length > 0 && bankStopsArePruned())
		{
			return false;
		}
		int withdrawLater = PlanContext.WITHDRAW_HALF_TICKS * countMissing(aWithdrawn, bWithdrawn);
		return aRank + withdrawLater <= bRank && aHalf + withdrawLater <= bHalf && spendsNoMore(aSpent, bSpent);
	}

	private static int countMissing(int[] sortedHave, int[] sortedWanted)
	{
		int count = 0;
		int i = 0;
		for (int id : sortedWanted)
		{
			while (i < sortedHave.length && sortedHave[i] < id)
			{
				i++;
			}
			if (i >= sortedHave.length || sortedHave[i] != id)
			{
				count++;
			}
		}
		return count;
	}

	/** An edge on the label's own leg that keeps its bank state and transport, spends nothing and ranks as it takes. */
	private void move(Label from, int node, int k, int half, Leg.Kind kind, int[] withdrawn)
	{
		offer(from, node, from.b, k, from.t, from.leg, half, half, kind, null, null, from.spent, withdrawn);
	}

	private static int withdrawHalfTicks(Label from, int[] withdrawn)
	{
		return PlanContext.WITHDRAW_HALF_TICKS * (withdrawn.length - from.withdrawn.length);
	}

	/** Returns null when dominated. */
	private Label offer(Label from, int node, int b, int k, int t, int leg, int addHalf, int addRank, Leg.Kind kind,
		TransportOptions.Edge ref, TransportOptions.Edge ref2, int[] spent, int[] withdrawn)
	{
		int half = (from == null ? 0 : from.half) + addHalf;
		int rank = (from == null ? 0 : from.rank) + addRank;
		int bound = bounds[leg] == null ? 0 : bounds[leg].of(node);
		if (bound >= LowerBound.UNREACHABLE)
		{
			return null;
		}
		int base = firstSlot(leg, node, b, k, t);
		if (dominatedAt(base, b, rank, half, spent, withdrawn))
		{
			return null;
		}
		killDominated(base, b, rank, half, spent, withdrawn);
		int free = -1;
		int worst = -1;
		for (int i = base; i < base + MAX_LABELS; i++)
		{
			Label old = slots[i];
			if (old == null || old.dead)
			{
				free = free < 0 ? i : free;
			}
			else if (worst < 0 || old.rank > slots[worst].rank)
			{
				worst = i;
			}
		}
		if (free < 0)
		{
			if (slots[worst].rank <= rank)
			{
				return null;
			}
			slots[worst].dead = true;
			free = worst;
		}
		Label label = new Label();
		label.leg = leg;
		label.node = node;
		label.b = b;
		label.k = k;
		label.t = t;
		label.half = half;
		label.rank = rank;
		label.f = rank + bound;
		label.spent = spent;
		label.withdrawn = withdrawn;
		label.parent = from;
		label.choicePenalty = from == null ? 0 : from.choicePenalty;
		label.fee = from == null ? 0 : from.fee;
		label.kind = kind;
		label.ref = ref;
		label.ref2 = ref2;
		label.edgeHalf = addHalf;
		slots[free] = label;
		queue.add(label);
		labels++;
		return label;
	}

	private boolean dominatedAt(int base, int b, int rank, int half, int[] spent, int[] withdrawn)
	{
		for (int i = base; i < base + MAX_LABELS; i++)
		{
			Label old = slots[i];
			if (old != null && !old.dead && dominates(b, old.rank, old.half, old.spent, old.withdrawn, rank, half, spent, withdrawn))
			{
				return true;
			}
		}
		return false;
	}

	private void killDominated(int base, int b, int rank, int half, int[] spent, int[] withdrawn)
	{
		for (int i = base; i < base + MAX_LABELS; i++)
		{
			Label old = slots[i];
			if (old != null && !old.dead && dominates(b, rank, half, spent, withdrawn, old.rank, old.half, old.spent, old.withdrawn))
			{
				old.dead = true;
				slots[i] = null;
			}
		}
	}

	/** The first of the state's {@link #MAX_LABELS} slots. */
	private int firstSlot(int leg, int node, int b, int k, int t)
	{
		return ((((leg * nodes + node) * 2 + b) * BOOKS + k) * 2 + t) * MAX_LABELS;
	}

	/**
	 * Null when the bank state hasn't enough. A resource the player has plenty of (a stack of 1,000 runes) is never
	 * tracked, so it doesn't split labels.
	 */
	private int[] spend(int[] spent, int[] res, int[] amounts, int b)
	{
		if (spent == null || res.length == 0)
		{
			return spent;
		}
		int[] merged = spent;
		for (int i = 0; i < res.length; i++)
		{
			if (!ctx.resources.tracked(res[i]))
			{
				continue;
			}
			merged = add(merged, res[i], amounts[i]);
			if (count(merged, res[i]) > ctx.resources.available(res[i], b))
			{
				return null;
			}
		}
		return merged;
	}

	/** {@code pairs} is flat (resource, units) pairs, sorted by resource. */
	private static int[] add(int[] pairs, int resource, int units)
	{
		for (int i = 0; i < pairs.length; i += 2)
		{
			if (pairs[i] == resource)
			{
				int[] copy = pairs.clone();
				copy[i + 1] += units;
				return copy;
			}
		}
		int[] copy = new int[pairs.length + 2];
		int at = 0;
		int i = 0;
		while (i < pairs.length && pairs[i] < resource)
		{
			copy[at++] = pairs[i++];
			copy[at++] = pairs[i++];
		}
		copy[at++] = resource;
		copy[at++] = units;
		while (i < pairs.length)
		{
			copy[at++] = pairs[i++];
		}
		return copy;
	}

	static int count(int[] pairs, int resource)
	{
		for (int i = 0; i < pairs.length; i += 2)
		{
			if (pairs[i] == resource)
			{
				return pairs[i + 1];
			}
		}
		return 0;
	}

	private int capeUses(int[] spent)
	{
		return ctx.resources.cape < 0 ? 0 : count(spent, ctx.resources.cape);
	}

	private static boolean spendsNoMore(int[] a, int[] b)
	{
		for (int i = 0; i < a.length; i += 2)
		{
			if (a[i + 1] > count(b, a[i]))
			{
				return false;
			}
		}
		return true;
	}

	private int effectiveBook(int k, int half)
	{
		return k >= FIRST_PENDING && half > revertHalfTicks ? LUNAR : k;
	}

	private static int pendingIndex(Book book)
	{
		return book == Book.ARCEUUS ? FIRST_PENDING + 2 : FIRST_PENDING + book.ordinal();
	}

	private static Book pendingBook(int k)
	{
		return k == FIRST_PENDING + 2 ? Book.ARCEUUS : Book.values()[k - FIRST_PENDING];
	}

	/** A pending swap reverts to Lunar by itself, so a route that leaves it pending ends on Lunar. */
	static Book endBook(int k)
	{
		return k >= FIRST_PENDING ? Book.LUNAR : Book.values()[k];
	}

	static Book bookOf(int k)
	{
		return k >= FIRST_PENDING ? pendingBook(k) : Book.values()[k];
	}

	@FieldDefaults(level = AccessLevel.PACKAGE)
	static final class Label
	{
		int leg;
		int node;
		int b;
		int k;
		int t;
		int half;
		int rank;
		int f;
		/** The rank so far that only chooses between routes: margins on bank stops and walked changes, change tie-breaks. */
		int choicePenalty;
		/** The rank so far for what the route uses up: the Save consumables setting's fees. */
		int fee;
		int[] spent;
		/** Sorted; each id is charged its withdraw ticks once. */
		int[] withdrawn = NOTHING;
		Label parent;
		Leg.Kind kind;
		TransportOptions.Edge ref;
		TransportOptions.Edge ref2;
		int edgeHalf;
		Book endBook;
		boolean dead;

		List<Label> chain()
		{
			List<Label> chain = new ArrayList<>();
			for (Label l = this; l != null; l = l.parent)
			{
				chain.add(0, l);
			}
			return chain;
		}
	}
}
