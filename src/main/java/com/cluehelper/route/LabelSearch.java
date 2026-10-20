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
 * PLAN §5.4's label-setting Dijkstra. A state is (leg, node, banked, change category, book, has-transport);
 * a label at a state carries its ticks, rank and {@code spent}. Labels at one state are kept while neither
 * dominates the other (no more rank or ticks, a subset of spent, enough ahead to withdraw what it lacks), at most 4.
 * <p>
 * The change category (none, carried change, walked change) and banked are state dimensions, so the goal's
 * (banked, category) states hold each feature set's best route at once: Best, Direct and Same book never mix
 * feature sets (R13). Spellbook Swap is no state: it is fused into the spell edge it enables. A pending revert
 * is a book of its own (the temporary book) that turns into Lunar on its free cast, or by itself once the
 * route's time passes the swap's remaining window.
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
	/** How far past Best a search keeps looking for Same book (a route with no change at all). */
	private static final int SAME_BOOK_SLACK_HALF_TICKS = 120;
	private static final int SURFACE_WILDERNESS_MAX_Y = 3972;
	private static final int[] NOTHING = new int[0];

	private final PlanContext ctx;
	private final List<int[]> legTargets;
	private final boolean probes;
	private final int areaHalfTicks;
	private final int nodes;
	private final int revertHalfTicks;
	private final Set<String> excluded;
	private final boolean requireTransport;
	private final boolean wantSameBook;
	private final int rankLimit;
	private final Label[] slots;
	private final LowerBound[] bounds;
	private final PriorityQueue<Label> queue = new PriorityQueue<>((a, b) -> a.f != b.f ? a.f - b.f : a.half - b.half);

	/** Best label per bank state and change category at the goal; null where none was found. */
	final Label[][] goals = new Label[2][CATEGORIES];
	/** PROBES: per target node, per bank state and category, the best arrival. */
	final Map<Integer, Label[][]> probeBest = new HashMap<>();
	int labels;

	/**
	 * @param legTargets per leg, the nodes that complete it (one leg for ANY_OF, AREA and PROBES)
	 * @param areaHalfTicks AREA's expected search, added at the goal
	 * @param excluded transports ({@link Route#getFirstTransportKey()} form) left out, to find alternatives;
	 *                 {@link Route#WALK} leaves out routes with no transport
	 */
	LabelSearch(PlanContext ctx, List<int[]> legTargets, boolean probes, int areaHalfTicks, Set<String> excluded)
	{
		this(ctx, legTargets, probes, areaHalfTicks, excluded, Integer.MAX_VALUE);
	}

	/** @param rankLimit routes ranked above this are of no interest (an alternative far behind Best) */
	LabelSearch(PlanContext ctx, List<int[]> legTargets, boolean probes, int areaHalfTicks, Set<String> excluded, int rankLimit)
	{
		this.rankLimit = rankLimit;
		this.ctx = ctx;
		this.legTargets = legTargets;
		this.probes = probes;
		this.areaHalfTicks = areaHalfTicks;
		this.excluded = excluded;
		this.requireTransport = excluded.contains(Route.WALK);
		this.wantSameBook = excluded.isEmpty();
		this.nodes = ctx.nodeCount();
		this.revertHalfTicks = ctx.startPending ? 2 * ctx.in.getRevertTicksLeft() : -1;
		this.slots = new Label[legTargets.size() * nodes * STATES_PER_NODE * MAX_LABELS];
		this.bounds = new LowerBound[legTargets.size()];
		for (int leg = 0; leg < bounds.length; leg++)
		{
			bounds[leg] = probes ? null : ctx.lowerBound(legTargets.get(leg));
		}
	}

	/**
	 * Pops labels in rank order. Stops once nothing left can matter: past the best plain route (no bank stop, no
	 * walked change), since a route needing the margin must beat it; but while that route uses a change, on to
	 * Same book within a slack (not when looking for alternatives).
	 */
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

	/** @param f the popped label's rank plus its lower bound to the goal */
	private boolean pastEverythingUseful(int f)
	{
		Label plain = goals[0][NONE] != null ? goals[0][NONE] : goals[0][CHANGED];
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

	/**
	 * Past the best plain route only Same book (no change at all) can still be found, so labels that already changed
	 * book are dropped there instead of expanded.
	 */
	private boolean pastPlain(int f)
	{
		Label plain = goals[0][NONE] != null ? goals[0][NONE] : goals[0][CHANGED];
		return plain != null && f > plain.rank;
	}

	private int startNode()
	{
		PlanStart start = ctx.in.getStart();
		return start.getKind() == PlanStart.Kind.AT_NODE ? start.getNode() : ctx.start;
	}

	/** At an open bank the plan starts banked, with no stop to pay for. */
	private int startBankState()
	{
		return ctx.in.isAtOpenBank() && ctx.options[PlanContext.BANKED] != null ? PlanContext.BANKED : PlanContext.NOT_BANKED;
	}

	private int startBook()
	{
		return ctx.startPending ? pendingIndex(ctx.startBook) : ctx.startBook.ordinal();
	}

	// ---------------------------------------------------------------- expansion

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
		expandFairyRing(from, k);
		expandLocatedChange(from, origin, k);
		expandBank(from, k);
	}

	private void arrive(Label from, int k)
	{
		if (from.leg + 1 < legTargets.size())
		{
			offer(from, from.node, from.b, from.c, k, from.t, from.leg + 1, 0, 0, null, null, null, from.spent);
			return;
		}
		if (from.b == PlanContext.NOT_BANKED && !ctx.arrivesWithoutBank())
		{
			return;
		}
		int[] withdrawn = from.b == PlanContext.BANKED ? union(from.withdrawn, ctx.requiredWithdraw) : from.withdrawn;
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
		boolean onlyToBankUses = pointlessAfterBank(from, from.withdrawn);
		for (int e = table.entriesFrom(from.node); e < table.entriesEnd(from.node); e++)
		{
			int cost = ctx.gates.cost(e);
			int to = table.entryTo(e);
			if (cost != WalkTable.NO_PATH && !ctx.forbidden(to) && (!onlyToBankUses || ctx.usesBankItems(to)))
			{
				offer(from, to, from.b, from.c, k, from.t, from.leg, cost, cost, Leg.Kind.WALK, null, null, from.spent);
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
		SceneBfs.Result scene = start.getScene();
		WalkTable table = ctx.table;
		for (int node = 0; node < table.nodeCount(); node++)
		{
			int cost = scene.cost(table.placed(node));
			if (cost != SceneBfs.UNREACHED && !ctx.forbidden(node))
			{
				offer(from, node, from.b, from.c, k, from.t, from.leg, cost, cost, Leg.Kind.WALK, null, null, from.spent);
			}
		}
		for (int[] targets : legTargets)
		{
			for (int node : targets)
			{
				int cost = ctx.isTableNode(node) ? SceneBfs.UNREACHED : scene.cost(ctx.tile(node));
				if (cost != SceneBfs.UNREACHED)
				{
					offer(from, node, from.b, from.c, k, from.t, from.leg, cost, cost, Leg.Kind.WALK, null, null, from.spent);
				}
			}
		}
	}

	/** Walking due south out of the surface Wilderness to where more teleports work (PLAN §5.4). */
	private void expandExits(Label from, int k)
	{
		WorldPoint tile = ctx.tile(from.node);
		OriginClass origin = ctx.originClass(from.node);
		if (tile == null || !origin.isWilderness() || tile.getY() > SURFACE_WILDERNESS_MAX_Y)
		{
			return;
		}
		exit(from, k, tile, origin, OriginClass.WILD30, OriginClass.EXIT_WILD30_Y);
		exit(from, k, tile, origin, OriginClass.WILD20, OriginClass.EXIT_WILD20_Y);
		exit(from, k, tile, origin, OriginClass.WILD0, OriginClass.EXIT_WILD0_Y);
	}

	private void exit(Label from, int k, WorldPoint tile, OriginClass origin, OriginClass to, int exitY)
	{
		if (to.ordinal() < origin.ordinal() && tile.getY() > exitY)
		{
			int tiles = tile.getY() - exitY;
			offer(from, ctx.abstractNode(to), from.b, from.c, k, from.t, from.leg, tiles, tiles, Leg.Kind.WILDERNESS_EXIT, null, null, from.spent);
		}
	}

	private void expandTeleports(Label from, OriginClass origin, int k)
	{
		PlanContext.Options options = ctx.options[from.b];
		PlanContext.Change swap = ctx.in.getSettings().getSpellbookChanges() != SpellbookChanges.OFF ? options.swap : null;
		for (PlanContext.Teleport tele : options.teleports)
		{
			if (!origin.allows(tele.maxWilderness) || origin == OriginClass.POH && tele.minigame || ctx.forbidden(tele.node)
				|| excluded.contains(Route.teleportKey(tele.row)))
			{
				continue;
			}
			int castBook = castBook(tele.book, k);
			if (castBook >= 0)
			{
				transport(from, tele.node, from.c, castBook, tele.halfTicks, Leg.Kind.TELEPORT, tele, null);
			}
			if (swap != null && k == LUNAR && tele.book != null && tele.book != Book.LUNAR)
			{
				transport(from, tele.node, Math.max(from.c, CHANGED), LUNAR, tele.halfTicks + swap.halfTicks, Leg.Kind.SWAP_TELEPORT, tele, swap);
			}
		}
	}

	/** The book after casting a row of {@code rowBook} from book {@code k}, or −1 if it can't be cast. */
	private static int castBook(Book rowBook, int k)
	{
		if (rowBook == null)
		{
			return k;
		}
		if (k < FIRST_PENDING)
		{
			return rowBook.ordinal() == k ? k : -1;
		}
		return rowBook == pendingBook(k) ? LUNAR : -1;
	}

	/** Magic and Max cape: to any expanded book, from anywhere teleports work (B04), ending a pending revert. */
	private void expandCape(Label from, OriginClass origin, int k)
	{
		PlanContext.Change cape = ctx.options[from.b].cape;
		if (cape == null || ctx.in.getSettings().getSpellbookChanges() != SpellbookChanges.ALL || !ctx.changesAllowed(origin))
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

	/** An altar or Tyss at this node: toggles its book, or back to Standard when already on it. */
	private void expandLocatedChange(Label from, OriginClass origin, int k)
	{
		PlanContext.Change change = ctx.options[from.b].located.get(from.node);
		if (change == null || ctx.in.getSettings().getSpellbookChanges() != SpellbookChanges.ALL || !ctx.changesAllowed(origin))
		{
			return;
		}
		Book toggles = change.change.getToggles();
		Book current = bookOf(k);
		Book next = current == toggles ? Book.STANDARD : toggles;
		if (ctx.expanded.contains(next) && (k >= FIRST_PENDING || next != current))
		{
			change(from, from.node, WALKED, next.ordinal(), change);
		}
	}

	private void expandFairyRing(Label from, int k)
	{
		PlanContext.Options options = ctx.options[from.b];
		PlanContext.Teleport entry = options.fairyEntries.get(from.node);
		if (entry == null)
		{
			return;
		}
		for (PlanContext.Teleport landing : options.fairyLandings)
		{
			if (landing.node != from.node && !ctx.forbidden(landing.node) && !excluded.contains(Route.fairyKey(landing.row)))
			{
				transport(from, landing.node, from.c, k, Math.max(entry.halfTicks, landing.halfTicks), Leg.Kind.FAIRY_RING, entry, landing);
			}
		}
	}

	/**
	 * The bank-stop edge, the only way into the banked state: walking past a bank flips nothing. What is withdrawn is
	 * charged on the edges that use it, since the withdraw list is only known edge by edge (PLAN §5.4 Cost).
	 */
	private void expandBank(Label from, int k)
	{
		if (from.b == PlanContext.NOT_BANKED && ctx.options[PlanContext.BANKED] != null && ctx.isUsableBank(from.node))
		{
			int half = ctx.bankStopHalfTicks;
			offer(from, from.node, PlanContext.BANKED, from.c, k, from.t, from.leg, half, half, Leg.Kind.BANK, null, null, from.spent);
		}
	}

	private void transport(Label from, int node, int c, int k, int half, Leg.Kind kind, PlanContext.Teleport tele, Object second)
	{
		int[] spent = spend(from.spent, tele.res, tele.amounts, from.b);
		int[] withdrawn = union(from.withdrawn, tele.withdraw);
		if (second instanceof PlanContext.Change)
		{
			PlanContext.Change swap = (PlanContext.Change) second;
			spent = spend(spent, swap.res, swap.amounts, from.b);
			withdrawn = union(withdrawn, swap.withdraw);
		}
		else if (second instanceof PlanContext.Teleport)
		{
			PlanContext.Teleport landing = (PlanContext.Teleport) second;
			spent = spend(spent, landing.res, landing.amounts, from.b);
			withdrawn = union(withdrawn, landing.withdraw);
		}
		if (spent != null && !pointlessAfterBank(from, withdrawn))
		{
			int total = half + PlanContext.WITHDRAW_HALF_TICKS * (withdrawn.length - from.withdrawn.length);
			int penalty = 2 * ctx.in.getSettings().getConsumablePenalty() * tele.consumed;
			offer(from, node, from.b, c, k, 1, from.leg, total, total + penalty, kind, tele, second, spent, withdrawn);
		}
	}

	private void change(Label from, int node, int c, int k, PlanContext.Change change)
	{
		int[] spent = spend(from.spent, change.res, change.amounts, from.b);
		int[] withdrawn = union(from.withdrawn, change.withdraw);
		if (spent != null && !pointlessAfterBank(from, withdrawn))
		{
			int total = change.halfTicks + PlanContext.WITHDRAW_HALF_TICKS * (withdrawn.length - from.withdrawn.length);
			int penalty = change.capeUse ? 2 * ctx.in.getSettings().getCapeUsePenalty() : 0;
			offer(from, node, from.b, c, k, from.t, from.leg, total, total + penalty, Leg.Kind.SPELLBOOK_CHANGE,
				change, null, spent, withdrawn);
		}
	}

	/**
	 * A bank stop followed by a transport or change that takes nothing from the bank: the same route without the stop
	 * is faster, unless the step's required items need the bank anyway or the plan started at an open bank.
	 */
	private boolean pointlessAfterBank(Label from, int[] withdrawn)
	{
		return from.b == PlanContext.BANKED && withdrawn.length == 0 && ctx.arrivesWithoutBank() && !ctx.in.isAtOpenBank();
	}

	/** Sorted item ids in either. */
	static int[] union(int[] a, int[] b)
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
	 * Label a is at least as good as label b from here on: no more ticks or rank, no more of anything spent, and ahead by
	 * enough to withdraw later, at 2 ticks each, whatever b has withdrawn that a hasn't.
	 */
	private static boolean dominates(int aRank, int aHalf, int[] aSpent, int[] aWithdrawn, int bRank, int bHalf, int[] bSpent, int[] bWithdrawn)
	{
		int owed = PlanContext.WITHDRAW_HALF_TICKS * missing(aWithdrawn, bWithdrawn);
		return aRank + owed <= bRank && aHalf + owed <= bHalf && subset(aSpent, bSpent);
	}

	/** How many ids of {@code small} are not in {@code big} (both sorted). */
	private static int missing(int[] big, int[] small)
	{
		int count = 0;
		int i = 0;
		for (int id : small)
		{
			while (i < big.length && big[i] < id)
			{
				i++;
			}
			if (i >= big.length || big[i] != id)
			{
				count++;
			}
		}
		return count;
	}

	// ---------------------------------------------------------------- labels

	/**
	 * Adds the label unless another dominates it; returns it, or null when dominated. A label also dominates
	 * labels of a higher change category at the same place, book and bank state: whatever the higher one goes
	 * on to do, the lower one can do too, and a lower category never needs the margin.
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
			if (dominatedAt(stateIndex(leg, node, b, lower, k, t) * MAX_LABELS, rank, half, spent, withdrawn))
			{
				return null;
			}
		}
		for (int higher = c + 1; higher < CATEGORIES; higher++)
		{
			killDominated(stateIndex(leg, node, b, higher, k, t) * MAX_LABELS, rank, half, spent, withdrawn);
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
			if (dominates(old.rank, old.half, old.spent, old.withdrawn, rank, half, spent, withdrawn))
			{
				return null;
			}
			if (dominates(rank, half, spent, withdrawn, old.rank, old.half, old.spent, old.withdrawn))
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

	private boolean dominatedAt(int base, int rank, int half, int[] spent, int[] withdrawn)
	{
		for (int i = base; i < base + MAX_LABELS; i++)
		{
			Label old = slots[i];
			if (old != null && !old.dead && dominates(old.rank, old.half, old.spent, old.withdrawn, rank, half, spent, withdrawn))
			{
				return true;
			}
		}
		return false;
	}

	private void killDominated(int base, int rank, int half, int[] spent, int[] withdrawn)
	{
		for (int i = base; i < base + MAX_LABELS; i++)
		{
			Label old = slots[i];
			if (old != null && !old.dead && dominates(rank, half, spent, withdrawn, old.rank, old.half, old.spent, old.withdrawn))
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
	 * {@code spent} plus the units of the resources that can run out, or null when that would use more than the
	 * bank state has. A resource the player has plenty of (a stack of 1,000 runes) is never tracked, so it
	 * doesn't split labels.
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

	/** Pairs (resource, units) sorted by resource. */
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

	/** Every resource in {@code a} at most as used as in {@code b}. */
	private static boolean subset(int[] a, int[] b)
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

	// ---------------------------------------------------------------- books

	private int effectiveBook(int k, int half)
	{
		return k >= FIRST_PENDING && half > revertHalfTicks ? LUNAR : k;
	}

	private static int pendingIndex(Book book)
	{
		return book == Book.ARCEUUS ? FIRST_PENDING + 2 : FIRST_PENDING + book.ordinal();
	}

	static Book pendingBook(int k)
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
		for (int target : legTargets.get(leg))
		{
			if (target == node)
			{
				return true;
			}
		}
		return false;
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
		/** Rank plus the A* lower bound to the goal: the queue's order. */
		int f;
		int[] spent;
		/** Item ids taken from the bank so far, sorted; each costs its 2 ticks once. */
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
				RowAvailability.Confidence c = l.ref instanceof PlanContext.Teleport ? ((PlanContext.Teleport) l.ref).confidence
					: l.ref instanceof PlanContext.Change ? ((PlanContext.Change) l.ref).confidence : null;
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
