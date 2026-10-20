package com.cluehelper.route;

import com.cluehelper.SpellbookChanges;
import com.cluehelper.player.Book;
import com.cluehelper.player.Charge;
import com.cluehelper.player.Container;
import com.cluehelper.player.ItemUse;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.SpellbookState;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.Category;
import com.cluehelper.transport.RowAvailability;
import com.cluehelper.transport.SpellbookChange;
import com.cluehelper.transport.Spend;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/**
 * One plan's graph, built from the input (PLAN §5.4 Search): nodes with their origin class, the usable
 * teleports, fairy rings and spellbook changes per bank state, the resources they spend, and the books worth
 * expanding. Node ids: the walk table's, then the start, the four teleport (abstract) nodes, the goal, and
 * any tile the table lacks.
 */
final class PlanContext
{
	static final int NOT_BANKED = 0;
	static final int BANKED = 1;
	/** Bank stop, arrival to the next transport's first click: open 1 tick, leave 2 (B14). */
	static final int BANK_STOP_HALF_TICKS = 6;
	/** Shortest Path's Duration already is click → landing (B14). */
	static final int LEG_OVERHEAD_TICKS = 0;
	/** Each item stack withdrawn, worn or deposited at the bank: 2 ticks (B14), charged on the edge that needs it. */
	static final int WITHDRAW_HALF_TICKS = 4;
	/** The bank PIN on the first open of a session (B14: 3–4 ticks). */
	static final int PIN_HALF_TICKS = 8;
	/** "Deposit first" when the withdraw list needs more slots than are free: one more click (PLAN §5.4 Edge cases). */
	static final int DEPOSIT_FIRST_HALF_TICKS = 2;
	static final int SWAP_DURATION_TICKS = 4;
	static final int SWAP_MENU_TICKS = 1;
	private static final OriginClass[] ABSTRACT_CLASSES = {OriginClass.WILD0, OriginClass.WILD20, OriginClass.WILD30, OriginClass.POH};

	final PlanInput in;
	final WalkTable table;
	final WalkGates gates;
	final int start;
	final int goal;
	private final int abstractBase;
	private final List<WorldPoint> extraTiles = new ArrayList<>();
	private final Map<WorldPoint, Integer> extraNodes = new HashMap<>();
	private OriginClass[] classes;
	private final Map<String, LowerBound> lowerBounds = new HashMap<>();

	final Resources resources = new Resources();
	final Options[] options = new Options[2];
	/** Books a route may move to (PLAN §5.4 Expansion); the start book is always in. */
	final Set<Book> expanded = EnumSet.noneOf(Book.class);
	final Book startBook;
	/** The book a strand check compares against: Lunar while a Swap is pending. */
	final Book homeBook;
	final boolean startPending;
	final boolean targetInWilderness;
	/** Arrival to the next transport's first click, PIN and the player's own extra included. */
	final int bankStopHalfTicks;
	/** How the step's required items can be met; arrivals without the bank don't count when only the bank meets them. */
	final RequiredItems.Verdict required;
	/** Item ids the required items withdraw, sorted. */
	final int[] requiredWithdraw;
	private byte[] bankUsable;
	private boolean[] bankItemUses;

	PlanContext(PlanInput in)
	{
		this.in = in;
		this.table = in.getTable();
		this.gates = new WalkGates(table, in.getPlayer(), in.getEngine(), in.getNowMinutes());
		int t = table.nodeCount();
		start = t;
		abstractBase = t + 1;
		goal = abstractBase + ABSTRACT_CLASSES.length;
		SpellbookState book = in.getPlayer().getSpellbook();
		boolean pending = book != null && book.isPendingRevert() && in.getRevertTicksLeft() > 0 && book.getBook() != Book.LUNAR;
		startPending = pending;
		startBook = book == null || book.getBook() == null ? Book.STANDARD : book.isPendingRevert() && !pending ? Book.LUNAR : book.getBook();
		homeBook = book != null && book.isPendingRevert() ? Book.LUNAR : startBook;
		targetInWilderness = in.getStep().getTargets().stream().anyMatch(tile -> OriginClass.of(tile, in.getNoTeleport()).isWilderness());
		options[NOT_BANKED] = new Options(in.getPlayer(), NOT_BANKED);
		if (in.getSettings().isBankEdge() && in.getBanked() != null)
		{
			options[BANKED] = new Options(in.getBanked(), BANKED);
		}
		bankStopHalfTicks = 2 * in.getSettings().getBankStopExtraTicks() + BANK_STOP_HALF_TICKS + (in.isPinPending() ? PIN_HALF_TICKS : 0);
		required = in.getRequired().judge(in.getPlayer(), options[BANKED] == null ? null : in.getBanked());
		requiredWithdraw = required.getWithdraw().stream().mapToInt(ItemUse::getItemId).distinct().sorted().toArray();
		expandBooks();
	}

	/** Whether an arrival without a bank stop completes the step: its required items are carried, or nothing meets them. */
	boolean arrivesWithoutBank()
	{
		return required.isCarried() || !required.isBanked();
	}

	/**
	 * A fairy ring or altar here takes something from the bank (a staff, runes): the only places worth walking to after
	 * a bank stop before anything is withdrawn.
	 */
	boolean usesBankItems(int node)
	{
		Options banked = options[BANKED];
		if (banked == null)
		{
			return false;
		}
		if (bankItemUses == null)
		{
			bankItemUses = new boolean[nodeCount()];
			banked.fairyEntries.forEach((at, entry) -> bankItemUses[at] |= entry.withdraw.length > 0
				|| banked.fairyLandings.stream().anyMatch(landing -> landing.withdraw.length > 0));
			banked.located.forEach((at, change) -> bankItemUses[at] |= change.withdraw.length > 0);
		}
		return node < bankItemUses.length && bankItemUses[node];
	}

	/** A bank stop here gives the banked state: a bank, not a deposit box, whose own gates pass (PLAN §5.4 Banked). */
	boolean isUsableBank(int node)
	{
		if (!isTableNode(node) || !table.is(node, WalkTable.KIND_BANK))
		{
			return false;
		}
		if (bankUsable == null)
		{
			bankUsable = new byte[table.nodeCount()];
		}
		if (bankUsable[node] == 0)
		{
			PlayerSnapshot judgedAt = in.getPlayer().toBuilder().wildernessLevel(0).build();
			boolean usable = table.banks(node).stream().anyMatch(bank -> !bank.isDepositOnly() && in.getEngine()
				.judge(CatalogRow.builder().name(bank.getName()).requirements(bank.getRequirements()).build(), judgedAt, in.getNowMinutes())
				.isAvailable());
			bankUsable[node] = (byte) (usable ? 1 : 2);
		}
		return bankUsable[node] == 1;
	}

	int nodeCount()
	{
		return goal + 1 + extraTiles.size();
	}

	int abstractNode(OriginClass origin)
	{
		for (int i = 0; i < ABSTRACT_CLASSES.length; i++)
		{
			if (ABSTRACT_CLASSES[i] == origin)
			{
				return abstractBase + i;
			}
		}
		return WalkTable.NO_NODE;
	}

	OriginClass abstractClass(int node)
	{
		return node >= abstractBase && node < goal ? ABSTRACT_CLASSES[node - abstractBase] : null;
	}

	boolean isTableNode(int node)
	{
		return node < table.nodeCount();
	}

	/** The node for a tile: the table's, else an extra node only start legs and teleports reach. */
	int nodeAt(WorldPoint tile)
	{
		int node = table.node(tile);
		if (node != WalkTable.NO_NODE)
		{
			return node;
		}
		return extraNodes.computeIfAbsent(tile, k ->
		{
			extraTiles.add(k);
			return goal + extraTiles.size();
		});
	}

	WorldPoint tile(int node)
	{
		if (isTableNode(node))
		{
			return table.tile(node);
		}
		if (node == start)
		{
			return in.getStart().getTile();
		}
		return node > goal ? extraTiles.get(node - goal - 1) : null;
	}

	/** Call once every node exists (after the targets are added). */
	void freezeNodes()
	{
		classes = new OriginClass[nodeCount()];
		for (int node = 0; node < classes.length; node++)
		{
			WorldPoint tile = tile(node);
			classes[node] = tile == null ? OriginClass.WILD0 : OriginClass.of(tile, in.getNoTeleport());
		}
	}

	OriginClass originClass(int node)
	{
		return classes[node];
	}

	/** One leg's A* bounds, shared by every search of this plan. */
	LowerBound lowerBound(int[] targets)
	{
		return lowerBounds.computeIfAbsent(Arrays.toString(targets), k -> new LowerBound(this, targets));
	}

	/** avoidWilderness: no landing or walk into the Wilderness unless the target itself is there. */
	boolean forbidden(int node)
	{
		return in.getSettings().isAvoidWilderness() && !targetInWilderness && classes[node].isWilderness();
	}

	boolean changesAllowed(OriginClass origin)
	{
		return origin.allowsTeleports();
	}

	/** Ranking cost of finishing on {@code book}: the cheapest way back, else the strand penalty (PLAN §5.4). */
	int strandHalfTicks(Book book, int bankState, int capeUsesSpent)
	{
		if (book == homeBook)
		{
			return 0;
		}
		Change cape = options[bankState].cape;
		SpellbookState state = in.getPlayer().getSpellbook();
		int usesLeft = state == null ? 0 : state.getCapeChangesLeft();
		if (cape != null && capeUsesSpent + 1 <= usesLeft)
		{
			return cape.halfTicks + 2 * in.getSettings().getCapeUsePenalty();
		}
		return 2 * in.getSettings().getStrandPenalty();
	}

	private void expandBooks()
	{
		expanded.add(startBook);
		if (startPending)
		{
			expanded.add(Book.LUNAR);
		}
		SpellbookChanges allowed = in.getSettings().getSpellbookChanges();
		if (allowed != SpellbookChanges.ALL)
		{
			return;
		}
		Options widest = options[options[BANKED] != null ? BANKED : NOT_BANKED];
		Set<Book> reachable = EnumSet.noneOf(Book.class);
		SpellbookState state = in.getPlayer().getSpellbook();
		for (Options o : options)
		{
			if (o == null)
			{
				continue;
			}
			if (o.cape != null && state != null)
			{
				reachable.add(Book.STANDARD);
				if (state.isAncientAccess())
				{
					reachable.add(Book.ANCIENT);
				}
				if (state.isLunarAccess())
				{
					reachable.add(Book.LUNAR);
				}
				if (state.isArceuusUnlocked())
				{
					reachable.add(Book.ARCEUUS);
				}
			}
			for (Change located : o.located.values())
			{
				reachable.add(located.change.getToggles());
				reachable.add(Book.STANDARD);
			}
		}
		for (Book book : reachable)
		{
			if (widest.teleports.stream().anyMatch(tele -> tele.book == book))
			{
				expanded.add(book);
			}
		}
	}

	/** A usable teleport or fairy ring in one bank state. */
	static final class Teleport
	{
		CatalogRow row;
		int node;
		int halfTicks;
		Book book;
		int[] res;
		int[] amounts;
		int consumed;
		/** What it takes from the bank or group storage, by item id (sorted), and as witness uses. */
		int[] withdraw;
		List<ItemUse> withdrawUses;
		RowAvailability.Confidence confidence;
		boolean minigame;
		int maxWilderness;
	}

	/** A usable spellbook change in one bank state. */
	static final class Change
	{
		SpellbookChange change;
		int node = WalkTable.NO_NODE;
		int halfTicks;
		int[] res;
		int[] amounts;
		int[] withdraw;
		List<ItemUse> withdrawUses;
		boolean capeUse;
		RowAvailability.Confidence confidence;
	}

	/** What one bank state (A_carried or A_banked) offers. */
	final class Options
	{
		final List<Teleport> teleports = new ArrayList<>();
		final Map<Integer, Teleport> fairyEntries = new HashMap<>();
		final List<Teleport> fairyLandings = new ArrayList<>();
		final Map<Integer, Change> located = new HashMap<>();
		Change cape;
		Change swap;

		Options(PlayerSnapshot player, int bankState)
		{
			PlayerSnapshot judgedAt = player.toBuilder().wildernessLevel(0).build();
			Map<String, Teleport> longestVariant = new LinkedHashMap<>();
			for (CatalogRow row : in.getCatalog().getTeleports())
			{
				RowAvailability judged = in.getEngine().judge(row, judgedAt, in.getNowMinutes());
				if (!judged.isAvailable())
				{
					continue;
				}
				Teleport tele = teleport(judged, player, bankState);
				if (row.getOrigin() != null)
				{
					tele.node = nodeAt(row.getOrigin());
					fairyEntries.merge(tele.node, tele, (a, b) -> a.halfTicks <= b.halfTicks ? a : b);
				}
				else if (row.getCode() != null)
				{
					fairyLandings.add(tele);
				}
				else
				{
					longestVariant.merge(variantKey(row), tele, (a, b) -> a.halfTicks >= b.halfTicks ? a : b);
				}
			}
			teleports.addAll(longestVariant.values());
			for (SpellbookChange change : in.getCatalog().getSpellbookChanges())
			{
				if (change.isInHouse() || in.getRefusedChanges().contains(change.getName()))
				{
					continue;
				}
				CatalogRow asRow = CatalogRow.builder().name(change.getName()).requirements(change.getRequirements()).build();
				RowAvailability judged = in.getEngine().judge(asRow, judgedAt, in.getNowMinutes());
				if (!judged.isAvailable())
				{
					continue;
				}
				Change c = change(change, judged, player, bankState);
				if (change.isTemporary())
				{
					swap = c;
				}
				else if (change.isAnywhere())
				{
					cape = cape == null ? c : cape;
				}
				else
				{
					c.node = nodeAt(change.getTile());
					located.put(c.node, c);
				}
			}
		}

		/** Home and minigame rows come once per animation override; with several on, the game picks one, so the longest counts. */
		private String variantKey(CatalogRow row)
		{
			return row.getCategory() + "|" + row.getName() + "|" + row.getDestination() + "|" + row.getRequirements().getBook();
		}

		private Teleport teleport(RowAvailability judged, PlayerSnapshot player, int bankState)
		{
			CatalogRow row = judged.getRow();
			Teleport tele = new Teleport();
			tele.row = row;
			tele.node = row.getDestination() == null ? WalkTable.NO_NODE : nodeAt(row.getDestination());
			tele.halfTicks = 2 * (row.getDuration() + row.getMenuTicks() + LEG_OVERHEAD_TICKS);
			tele.book = row.getRequirements().getBook();
			tele.confidence = judged.getConfidence();
			tele.minigame = row.getCategory() == Category.MINIGAME;
			tele.maxWilderness = row.getRequirements().getMaxWildernessLevel();
			Spending spending = spending(row.getSpends(), judged.getWitness(), player, bankState);
			tele.res = spending.res;
			tele.amounts = spending.amounts;
			tele.consumed = spending.consumed;
			tele.withdrawUses = withdrawUses(judged.getWitness());
			tele.withdraw = ids(tele.withdrawUses);
			return tele;
		}

		private Change change(SpellbookChange change, RowAvailability judged, PlayerSnapshot player, int bankState)
		{
			Change c = new Change();
			c.change = change;
			c.halfTicks = change.isTemporary() ? 2 * (SWAP_DURATION_TICKS + SWAP_MENU_TICKS) : 2 * (change.getDuration() + change.getMenuTicks());
			c.capeUse = change.getSpends().stream().anyMatch(s -> s.getKind() == Spend.Kind.CAPE_USE);
			c.confidence = judged.getConfidence();
			Spending spending = spending(change.getSpends(), judged.getWitness(), player, bankState);
			c.res = spending.res;
			c.amounts = spending.amounts;
			c.withdrawUses = withdrawUses(judged.getWitness());
			c.withdraw = ids(c.withdrawUses);
			return c;
		}
	}

	private static List<ItemUse> withdrawUses(List<ItemUse> witness)
	{
		List<ItemUse> stored = new ArrayList<>();
		for (ItemUse use : witness)
		{
			if (use.getContainer() == Container.BANK || use.getContainer() == Container.GROUP_STORAGE)
			{
				stored.add(use);
			}
		}
		return List.copyOf(stored);
	}

	private static int[] ids(List<ItemUse> uses)
	{
		return uses.stream().mapToInt(ItemUse::getItemId).distinct().sorted().toArray();
	}

	private static final class Spending
	{
		int[] res;
		int[] amounts;
		int consumed;
	}

	/** Turns a row's Spends and its witness into resource units (PLAN §5.4 Consumption). */
	private Spending spending(List<Spend> spends, List<ItemUse> witness, PlayerSnapshot player, int bankState)
	{
		Map<Integer, Integer> units = new LinkedHashMap<>();
		int consumed = 0;
		for (Spend spend : spends)
		{
			switch (spend.getKind())
			{
				case RUNES:
				case ITEM:
					for (ItemUse use : witness)
					{
						if (use.getContainer() != Container.WORN)
						{
							int r = resources.item(use.getItemId(), player, bankState);
							units.merge(r, use.getQuantity(), Integer::sum);
							consumed += spend.getKind() == Spend.Kind.ITEM ? use.getQuantity() : 0;
						}
					}
					break;
				case CHARGE:
					for (ItemUse use : witness)
					{
						units.merge(resources.charge(use.getItemId(), player, bankState), 1, Integer::sum);
						consumed++;
					}
					break;
				case COOLDOWN:
					units.merge(resources.once("cooldown:" + spend.getVar(), bankState), 1, Integer::sum);
					break;
				case DAILY:
					units.merge(resources.once("daily:" + spend.getVar(), bankState), 1, Integer::sum);
					break;
				default:
					SpellbookState state = player.getSpellbook();
					units.merge(resources.capeUses(state == null ? 0 : state.getCapeChangesLeft(), bankState), 1, Integer::sum);
			}
		}
		units.forEach(resources::used);
		Spending s = new Spending();
		s.res = units.keySet().stream().mapToInt(Integer::intValue).toArray();
		s.amounts = units.values().stream().mapToInt(Integer::intValue).toArray();
		s.consumed = consumed;
		return s;
	}

	/** Every resource a plan's rows spend, with how many units each bank state has. */
	static final class Resources
	{
		/** No route uses one resource more often than this, so a resource with this many uses to spare can't run out. */
		private static final int MOST_USES_PER_ROUTE = 8;

		private final Map<String, Integer> ids = new LinkedHashMap<>();
		private final List<int[]> available = new ArrayList<>();
		private final List<Integer> mostPerUse = new ArrayList<>();
		int cape = -1;

		int item(int itemId, PlayerSnapshot player, int bankState)
		{
			int have = 0;
			for (Container container : Container.values())
			{
				have += player.count(container, itemId);
			}
			return set("item:" + itemId, bankState, have);
		}

		int charge(int itemId, PlayerSnapshot player, int bankState)
		{
			Charge charge = player.getCharges().get(itemId);
			int perItem = charge == null ? 1 : Math.max(0, charge.getCharges());
			int held = player.count(Container.INVENTORY, itemId) + player.count(Container.WORN, itemId)
				+ player.count(Container.BANK, itemId) + player.count(Container.GROUP_STORAGE, itemId);
			return set("charge:" + itemId, bankState, Math.max(1, held) * perItem);
		}

		int once(String key, int bankState)
		{
			return set(key, bankState, 1);
		}

		int capeUses(int left, int bankState)
		{
			cape = set("cape", bankState, left);
			return cape;
		}

		int available(int resource, int bankState)
		{
			return available.get(resource)[bankState];
		}

		void used(int resource, int units)
		{
			mostPerUse.set(resource, Math.max(mostPerUse.get(resource), units));
		}

		/** Whether labels must count this resource: it could run out within one route. */
		boolean tracked(int resource)
		{
			int most = Math.max(available.get(resource)[0], available.get(resource)[1]);
			return resource == cape || most < MOST_USES_PER_ROUTE * mostPerUse.get(resource);
		}

		String name(int resource)
		{
			return new ArrayList<>(ids.keySet()).get(resource);
		}

		private int set(String key, int bankState, int units)
		{
			Integer id = ids.get(key);
			if (id == null)
			{
				id = ids.size();
				ids.put(key, id);
				available.add(new int[2]);
				mostPerUse.add(0);
			}
			available.get(id)[bankState] = Math.max(available.get(id)[bankState], units);
			return id;
		}
	}
}
