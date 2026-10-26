package com.cluehelper.route;

import com.cluehelper.SpellbookChanges;
import com.cluehelper.player.Book;
import com.cluehelper.transport.RowAvailability;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/**
 * Label-setting Dijkstra over states (leg, node, banked b, change category c, book k, has-transport t).
 * <p>
 * Banked and change category are state dimensions so that the goal's states hold each feature set's best route at
 * once, and Best, Direct and Same book never mix feature sets. Spellbook Swap is fused into the spell edge it
 * enables. A pending revert is a book of its own that turns into Lunar on its free cast, or once its window passes.
 */
final class LabelSearch
{
	static final int NONE = 0;
	static final int CHANGED = 1;
	static final int WALKED = 2;
	static final int CATEGORIES = 3;
	private static final int BOOKS = 7;
	private static final int FIRST_PENDING = 4;
	private static final int LUNAR = Book.LUNAR.ordinal();
	private static final int MAX_LABELS = 4;
	private static final int STATES_PER_NODE = 2 * CATEGORIES * BOOKS * 2;
	private static final int SAME_BOOK_SLACK_HALF_TICKS = 120;
	private static final int SURFACE_WILDERNESS_MAX_Y = 3972;
	private static final int[] NOTHING = new int[0];
	private static final int CANNOT_CAST = -1;

	private final PlanContext ctx;
	private final List<int[]> legTargets;
	private final boolean probes;
	private final int areaHalfTicks;
	private final int nodes;
	private final int revertHalfTicks;
	private final Set<String> excluded;
	/** Only routes whose first transport has this key, a way into the house aside; null for any. */
	private final String onlyFirst;
	private final boolean requireTransport;
	private final boolean wantSameBook;
	private final int rankLimit;
	private final Label[] slots;
	private final LowerBound[] bounds;
	private final boolean[][] targets;
	private final PriorityQueue<Label> queue = new PriorityQueue<>((a, b) -> a.f != b.f ? a.f - b.f : a.half - b.half);

	final Label[][] goals = new Label[2][CATEGORIES];
	final Map<Integer, Label[][]> probeBest = new HashMap<>();
	int labels;

	/** @param excluded first-transport keys to leave out; {@link Route#WALK} leaves out routes with no transport */
	LabelSearch(PlanContext ctx, List<int[]> legTargets, boolean probes, int areaHalfTicks, Set<String> excluded, int rankLimit)
	{
		this(ctx, legTargets, probes, areaHalfTicks, excluded, null, rankLimit);
	}

	/** The routes that start with transport {@code onlyFirst} ({@link Route#WALK} for none), up to {@code rankLimit}. */
	LabelSearch(PlanContext ctx, List<int[]> legTargets, int areaHalfTicks, String onlyFirst, int rankLimit)
	{
		this(ctx, legTargets, false, areaHalfTicks, Set.of(), onlyFirst, rankLimit);
	}

	private LabelSearch(PlanContext ctx, List<int[]> legTargets, boolean probes, int areaHalfTicks, Set<String> excluded,
		String onlyFirst, int rankLimit)
	{
		this.rankLimit = rankLimit;
		this.ctx = ctx;
		this.legTargets = legTargets;
		this.probes = probes;
		this.areaHalfTicks = areaHalfTicks;
		this.excluded = excluded;
		this.onlyFirst = onlyFirst;
		this.requireTransport = excluded.contains(Route.WALK) || onlyFirst != null && !Route.WALK.equals(onlyFirst);
		this.wantSameBook = excluded.isEmpty() && onlyFirst == null;
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
		offer(null, startNode(), startBankState(), NONE, startBook(), 0, 0, 0, 0, null, null, null, new int[0]);
		while (!queue.isEmpty())
		{
			Label label = queue.poll();
			if (label.dead)
			{
				continue;
			}
			if (!probes && (label.f > rankLimit || pastEverythingUseful(label.f)))
			{
				break;
			}
			if (!probes && label.c != NONE && pastPlain(label.f))
			{
				continue;
			}
			if (label.node == ctx.goal)
			{
				if (goals[label.b][label.c] == null)
				{
					goals[label.b][label.c] = label;
				}
				continue;
			}
			expand(label);
		}
	}

	/**
	 * A route needing the margin must beat the best plain route, so nothing past it matters; while that route uses a
	 * change, Same book is still worth a slack past it.
	 */
	private boolean pastEverythingUseful(int f)
	{
		Label plain = bestPlainGoal();
		if (plain == null)
		{
			return false;
		}
		if (goals[0][NONE] != null || !wantSameBook)
		{
			return f > plain.rank;
		}
		return f > plain.rank + Math.max(plain.rank, SAME_BOOK_SLACK_HALF_TICKS);
	}

	/** Past the best plain route only Same book can still be found, so changed labels are dropped there. */
	private boolean pastPlain(int f)
	{
		Label plain = bestPlainGoal();
		return plain != null && f > plain.rank;
	}

	private Label bestPlainGoal()
	{
		Label[] noBankStop = goals[PlanContext.NOT_BANKED];
		return noBankStop[NONE] != null ? noBankStop[NONE] : noBankStop[CHANGED];
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
		if (isTarget(from.leg, from.node))
		{
			arrive(from, k);
		}
		expandWalks(from, k);
		OriginClass origin = ctx.originClass(from.node);
		if (origin.allowsTeleports())
		{
			offer(from, ctx.abstractNode(origin), from.b, from.c, k, from.t, from.leg, 0, 0, null, null, null, from.spent);
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
			if (tele.node != from.node && !ctx.forbidden(tele.node) && !excluded.contains(Route.teleportKey(tele.row)))
			{
				transport(from, tele.node, from.c, k, tele.halfTicks, Leg.Kind.TELEPORT, tele, null);
			}
		}
	}

	private void arrive(Label from, int k)
	{
		Book needed = ctx.stageBook(from.node);
		if (ctx.stageNeedsBank(from.node, from.b) || needed != null && bookOf(k) != needed)
		{
			return;
		}
		int[] staged = sortedUnion(from.withdrawn, ctx.stageWithdraw(from.node, from.b));
		if (from.leg + 1 < legTargets.size())
		{
			int half = PlanContext.WITHDRAW_HALF_TICKS * (staged.length - from.withdrawn.length);
			offer(from, from.node, from.b, from.c, k, from.t, from.leg + 1, half, half, null, null, null, from.spent, staged);
			return;
		}
		if (from.b == PlanContext.NOT_BANKED && !ctx.arrivesWithoutBank())
		{
			return;
		}
		int[] withdrawn = from.b == PlanContext.BANKED ? sortedUnion(staged, ctx.requiredWithdraw) : staged;
		int area = areaHalfTicks + PlanContext.WITHDRAW_HALF_TICKS * (withdrawn.length - from.withdrawn.length);
		Book end = endBook(k);
		int strand = ctx.strandHalfTicks(end, from.b, capeUses(from.spent));
		if (probes)
		{
			Label[][] best = probeBest.computeIfAbsent(from.node, n -> new Label[2][CATEGORIES]);
			Label current = best[from.b][from.c];
			if (current == null || current.rank > from.rank + area + strand)
			{
				best[from.b][from.c] = goalLabel(from, end, area, strand, withdrawn);
			}
			return;
		}
		if (!(requireTransport && from.t == 0))
		{
			Label goal = offer(from, ctx.goal, from.b, from.c, 0, 0, from.leg, area, area + strand, null, null, null, from.spent, withdrawn);
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
		goal.c = from.c;
		goal.half = from.half + area;
		goal.rank = from.rank + area + strand;
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
		WalkGates gates = ctx.gates(from.b);
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
				int total = Running.halfTicks(cost) + PlanContext.WITHDRAW_HALF_TICKS * (withdrawn.length - from.withdrawn.length);
				offer(from, to, from.b, from.c, k, from.t, from.leg, total, total, Leg.Kind.WALK, null, null, from.spent, withdrawn);
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
				int half = Running.halfTicks(cost);
				offer(from, node, from.b, from.c, k, from.t, from.leg, half, half, Leg.Kind.WALK, null, null, from.spent);
			}
		}
		for (int[] targets : legTargets)
		{
			for (int node : targets)
			{
				int cost = ctx.isTableNode(node) ? WalkCosts.UNREACHED : walk.cost(ctx.tile(node));
				if (cost != WalkCosts.UNREACHED)
				{
					int half = Running.halfTicks(cost);
					offer(from, node, from.b, from.c, k, from.t, from.leg, half, half, Leg.Kind.WALK, null, null, from.spent);
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
		if (to.ordinal() < origin.ordinal() && tile.getY() > to.exitY())
		{
			int half = Running.halfTicks(tile.getY() - to.exitY());
			offer(from, ctx.abstractNode(to), from.b, from.c, k, from.t, from.leg, half, half, Leg.Kind.WILDERNESS_EXIT, null, null, from.spent);
		}
	}

	private void expandTeleports(Label from, OriginClass origin, int k)
	{
		TransportOptions options = ctx.options[from.b];
		TransportOptions.Change swap = ctx.in.getSettings().getSpellbookChanges() != SpellbookChanges.OFF ? options.swap : null;
		for (TransportOptions.Teleport tele : options.teleports)
		{
			if (!origin.allows(tele.maxWilderness) || origin == OriginClass.POH && tele.minigame || ctx.forbidden(tele.node)
				|| excluded.contains(Route.teleportKey(tele.row)))
			{
				continue;
			}
			int bookAfter = bookAfterCasting(tele.book, k);
			if (bookAfter != CANNOT_CAST)
			{
				transport(from, tele.node, from.c, bookAfter, tele.halfTicks, Leg.Kind.TELEPORT, tele, null);
			}
			if (swap != null && k == LUNAR && tele.book != null && tele.book != Book.LUNAR)
			{
				transport(from, tele.node, Math.max(from.c, CHANGED), LUNAR, tele.halfTicks + swap.halfTicks, Leg.Kind.SWAP_TELEPORT, tele, swap);
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
				change(from, from.node, Math.max(from.c, CHANGED), book.ordinal(), cape);
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
					change(from, from.node, WALKED, next.ordinal(), change);
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
			if (landing.node != from.node && !ctx.forbidden(landing.node) && !excluded.contains(Route.fairyKey(landing.row)))
			{
				int half = Math.max(entry.halfTicks, landing.halfTicks) - (ctx.isLastFairyRing(landing.row) ? 2 * landing.row.getMenuTicks() : 0);
				transport(from, landing.node, from.c, k, half, Leg.Kind.FAIRY_RING, entry, landing);
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
			int half = ctx.bankStopHalfTicks;
			offer(from, from.node, PlanContext.BANKED, from.c, k, from.t, from.leg, half, half, Leg.Kind.BANK, null, null, from.spent);
			takeGateItems(from, k, half);
		}
	}

	/**
	 * A web's knife or sword, or a stage's gear, is used a few legs after the bank, where a stop that took nothing yet
	 * only walks to bank-item uses; so a second stop takes them at once.
	 */
	private void takeGateItems(Label from, int k, int bankHalfTicks)
	{
		int[] withdrawn = sortedUnion(sortedUnion(from.withdrawn, ctx.gates(PlanContext.BANKED).storedGateItems()), ctx.stageWithdraw());
		if (withdrawn.length > from.withdrawn.length)
		{
			int half = bankHalfTicks + PlanContext.WITHDRAW_HALF_TICKS * (withdrawn.length - from.withdrawn.length);
			offer(from, from.node, PlanContext.BANKED, from.c, k, from.t, from.leg, half, half, Leg.Kind.BANK, null, null, from.spent, withdrawn);
		}
	}

	private void transport(Label from, int node, int c, int k, int half, Leg.Kind kind, TransportOptions.Teleport tele, Object second)
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
		if (second instanceof TransportOptions.Change)
		{
			TransportOptions.Change swap = (TransportOptions.Change) second;
			spent = spend(spent, swap.res, swap.amounts, from.b);
			withdrawn = sortedUnion(withdrawn, swap.withdraw);
		}
		else if (second instanceof TransportOptions.Teleport)
		{
			TransportOptions.Teleport landing = (TransportOptions.Teleport) second;
			spent = spend(spent, landing.res, landing.amounts, from.b);
			withdrawn = sortedUnion(withdrawn, landing.withdraw);
		}
		if (spent != null && !pointlessAfterBank(from, withdrawn))
		{
			int total = half + PlanContext.WITHDRAW_HALF_TICKS * (withdrawn.length - from.withdrawn.length);
			offer(from, node, from.b, c, k, t, from.leg, total, total, kind, tele, second, spent, withdrawn);
		}
	}

	private void change(Label from, int node, int c, int k, TransportOptions.Change change)
	{
		int[] spent = spend(from.spent, change.res, change.amounts, from.b);
		int[] withdrawn = sortedUnion(from.withdrawn, change.withdraw);
		if (spent != null && !pointlessAfterBank(from, withdrawn))
		{
			int total = change.halfTicks + PlanContext.WITHDRAW_HALF_TICKS * (withdrawn.length - from.withdrawn.length);
			int penalty = change.capeUse ? PlanContext.CAPE_USE_PENALTY_HALF_TICKS : 0;
			offer(from, node, from.b, c, k, from.t, from.leg, total, total + penalty, Leg.Kind.SPELLBOOK_CHANGE,
				change, null, spent, withdrawn);
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

	/**
	 * Returns null when dominated. A lower change category also dominates a higher one at the same place, book and bank
	 * state: it can do whatever the higher one goes on to do, and never needs the margin.
	 */
	private Label offer(Label from, int node, int b, int c, int k, int t, int leg, int addHalf, int addRank, Leg.Kind kind,
		Object ref, Object ref2, int[] spent)
	{
		return offer(from, node, b, c, k, t, leg, addHalf, addRank, kind, ref, ref2, spent, from == null ? NOTHING : from.withdrawn);
	}

	private Label offer(Label from, int node, int b, int c, int k, int t, int leg, int addHalf, int addRank, Leg.Kind kind,
		Object ref, Object ref2, int[] spent, int[] withdrawn)
	{
		int half = (from == null ? 0 : from.half) + addHalf;
		int rank = (from == null ? 0 : from.rank) + addRank;
		int bound = bounds[leg] == null ? 0 : bounds[leg].of(node);
		if (bound >= LowerBound.UNREACHABLE)
		{
			return null;
		}
		for (int lower = 0; lower < c; lower++)
		{
			if (dominatedAt(stateIndex(leg, node, b, lower, k, t) * MAX_LABELS, b, rank, half, spent, withdrawn))
			{
				return null;
			}
		}
		for (int higher = c + 1; higher < CATEGORIES; higher++)
		{
			killDominated(stateIndex(leg, node, b, higher, k, t) * MAX_LABELS, b, rank, half, spent, withdrawn);
		}
		int base = stateIndex(leg, node, b, c, k, t) * MAX_LABELS;
		int free = -1;
		int worst = -1;
		for (int i = base; i < base + MAX_LABELS; i++)
		{
			Label old = slots[i];
			if (old == null || old.dead)
			{
				free = free < 0 ? i : free;
				continue;
			}
			if (dominates(b, old.rank, old.half, old.spent, old.withdrawn, rank, half, spent, withdrawn))
			{
				return null;
			}
			if (dominates(b, rank, half, spent, withdrawn, old.rank, old.half, old.spent, old.withdrawn))
			{
				old.dead = true;
				slots[i] = null;
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
		label.c = c;
		label.k = k;
		label.t = t;
		label.half = half;
		label.rank = rank;
		label.f = rank + bound;
		label.spent = spent;
		label.withdrawn = withdrawn;
		label.parent = from;
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

	private int stateIndex(int leg, int node, int b, int c, int k, int t)
	{
		return ((((leg * nodes + node) * 2 + b) * CATEGORIES + c) * BOOKS + k) * 2 + t;
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

	private boolean isTarget(int leg, int node)
	{
		return targets[leg][node];
	}

	static final class Label
	{
		int leg;
		int node;
		int b;
		int c;
		int k;
		int t;
		int half;
		int rank;
		int f;
		int[] spent;
		/** Sorted; each id is charged its withdraw ticks once. */
		int[] withdrawn = NOTHING;
		Label parent;
		Leg.Kind kind;
		Object ref;
		Object ref2;
		int edgeHalf;
		Book endBook;
		boolean dead;

		RowAvailability.Confidence confidence()
		{
			RowAvailability.Confidence worst = RowAvailability.Confidence.SURE;
			for (Label l = this; l != null; l = l.parent)
			{
				RowAvailability.Confidence c = l.ref instanceof TransportOptions.Teleport ? ((TransportOptions.Teleport) l.ref).confidence
					: l.ref instanceof TransportOptions.Change ? ((TransportOptions.Change) l.ref).confidence : null;
				if (c != null && c.ordinal() > worst.ordinal())
				{
					worst = c;
				}
			}
			return worst;
		}

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
