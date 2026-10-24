package com.cluehelper.route;

import com.cluehelper.SpellbookChanges;
import com.cluehelper.player.Book;
import com.cluehelper.player.Charge;
import com.cluehelper.player.Container;
import com.cluehelper.player.HouseState;
import com.cluehelper.player.ItemUse;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.PositionRules;
import com.cluehelper.player.SpellbookState;
import com.cluehelper.step.Stage;
import com.cluehelper.step.StepIndex;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.Category;
import com.cluehelper.transport.HouseNeed;
import com.cluehelper.transport.Requirements;
import com.cluehelper.transport.RowAvailability;
import com.cluehelper.transport.SpellbookChange;
import com.cluehelper.transport.Spend;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirements;

final class PlanContext
{
	static final int NOT_BANKED = 0;
	static final int BANKED = 1;
	/** Arrival to the next transport's first click: open 1 tick, leave 2. */
	static final int BANK_STOP_HALF_TICKS = 6;
	/** Shortest Path's Duration already runs from click to landing. */
	static final int LEG_OVERHEAD_TICKS = 0;
	/** Per item stack withdrawn, worn or deposited at the bank. */
	static final int WITHDRAW_HALF_TICKS = 4;
	static final int PIN_HALF_TICKS = 8;
	/** Ranking only: Magic and Max cape changes are limited per day. */
	static final int CAPE_USE_PENALTY_HALF_TICKS = 6;
	/** Ranking only: the route leaves you on another book with no way back left today. */
	static final int STRAND_PENALTY_HALF_TICKS = 60;
	/** One more click when the withdraw list needs more slots than are free. */
	static final int DEPOSIT_FIRST_HALF_TICKS = 2;
	static final int SWAP_DURATION_TICKS = 4;
	static final int SWAP_MENU_TICKS = 1;
	/** The house scene loads about a tick after the transport's own landing. */
	static final int HOUSE_LOAD_HALF_TICKS = 2;
	private static final OriginClass[] ABSTRACT_CLASSES = {OriginClass.WILD0, OriginClass.WILD20, OriginClass.WILD30, OriginClass.POH};

	final PlanInput in;
	final WalkTable table;
	final int start;
	final int goal;
	final int pohNode;
	private final int abstractBase;
	private final List<WorldPoint> extraTiles = new ArrayList<>();
	private final Map<WorldPoint, Integer> extraNodes = new HashMap<>();
	private OriginClass[] classes;
	private final Map<String, LowerBound> lowerBounds = new HashMap<>();

	final Resources resources = new Resources();
	final Options[] options = new Options[2];
	private final WalkGates[] gates = new WalkGates[2];
	/** Books a route may change to; always holds the start book. */
	final Set<Book> expanded = EnumSet.noneOf(Book.class);
	final Book startBook;
	final Book homeBook;
	final boolean startPending;
	final boolean targetInWilderness;
	/** Every target of the step, or of one of its stages, is in the Wilderness: avoiding it would leave no way there. */
	final boolean onlyInWilderness;
	final boolean avoidsWilderness;
	/** The master scroll book is carried and stays out of the Wilderness, where the target is: a bank stop comes first. */
	final boolean depositsBookFirst;
	final int bankStopHalfTicks;
	final RequiredItems.Verdict required;
	final int[] requiredWithdraw;
	/** By target node: what a part's act wants worn (Ghommal's +100 melee strength), on the player on arrival there. */
	private final Map<Integer, RequiredItems.Verdict> stageNeeds = new HashMap<>();
	private final List<RequiredItems.Verdict> missingEverywhere = new ArrayList<>();
	/** Stage target nodes that must be reached on a given book (the Arceuus lectern). */
	private final Map<Integer, Book> stageBooks = new HashMap<>();
	private final int[] stageWithdraw;
	private byte[] bankUsable;
	private boolean[] bankItemUses;
	private Map<Integer, Integer> rowsPerItem;

	PlanContext(PlanInput in)
	{
		this.in = in;
		this.table = in.getTable();
		int t = table.nodeCount();
		start = t;
		abstractBase = t + 1;
		goal = abstractBase + ABSTRACT_CLASSES.length;
		pohNode = table.node(PositionRules.POH_NODE);
		SpellbookState book = in.getPlayer().getSpellbook();
		boolean pending = book != null && book.isPendingRevert() && in.getRevertTicksLeft() > 0 && book.getBook() != Book.LUNAR;
		startPending = pending;
		startBook = book == null || book.getBook() == null ? Book.STANDARD : book.isPendingRevert() && !pending ? Book.LUNAR : book.getBook();
		homeBook = book != null && book.isPendingRevert() ? Book.LUNAR : startBook;
		targetInWilderness = in.getStep().getTargets().stream().anyMatch(tile -> OriginClass.of(tile, in.getNoTeleport()).isWilderness());
		onlyInWilderness = allInWilderness(in.getStep().getTargets())
			|| in.getStep().getStages().stream().anyMatch(stage -> allInWilderness(stage.getTiles()));
		avoidsWilderness = in.getSettings().isAvoidWilderness() && !onlyInWilderness;
		PlayerSnapshot carrier = in.getPlayer();
		depositsBookFirst = in.getSettings().isScrollBookOutOfWilderness() && targetInWilderness
			&& (carrier.getInventory().containsKey(BookScrolls.BOOK) || carrier.getWorn().containsKey(BookScrolls.BOOK));
		options[NOT_BANKED] = new Options(in.getPlayer(), NOT_BANKED);
		if (in.getSettings().isBankEdge() && in.getBanked() != null)
		{
			options[BANKED] = new Options(in.getBanked(), BANKED);
			gates[BANKED] = new WalkGates(table, in.getBanked(), in.getEngine(), in.getNowMinutes());
		}
		gates[NOT_BANKED] = new WalkGates(table, in.getPlayer(), in.getEngine(), in.getNowMinutes());
		bankStopHalfTicks = BANK_STOP_HALF_TICKS + (in.isPinPending() ? PIN_HALF_TICKS : 0);
		PlayerSnapshot bankedPlayer = options[BANKED] == null ? null : in.getBanked();
		Set<RequiredItems.Need> atStages = judgeStageNeeds(bankedPlayer);
		required = in.getRequired().toBuilder()
			.needs(in.getRequired().getNeeds().stream().filter(need -> !atStages.contains(need)).collect(Collectors.toList()))
			.build().judge(in.getPlayer(), bankedPlayer);
		requiredWithdraw = required.getWithdraw().stream().mapToInt(ItemUse::getItemId).distinct().sorted().toArray();
		stageWithdraw = stageNeeds.values().stream().flatMap(verdict -> verdict.getWithdraw().stream()).mapToInt(ItemUse::getItemId)
			.distinct().sorted().toArray();
		expandBooks();
	}

	/** After a bank stop a gate may also take a stored item, such as a knife for a web. */
	WalkGates gates(int bankState)
	{
		return gates[bankState];
	}

	/**
	 * The needs some stage wants on arrival (a part's worn gear, Sherlock's task items); each such stage's verdict is kept
	 * by its target nodes. A stage done anywhere has no node, so its needs stay the step's, carried from the start.
	 */
	private Set<RequiredItems.Need> judgeStageNeeds(PlayerSnapshot bankedPlayer)
	{
		Set<RequiredItems.Need> atStages = new HashSet<>();
		for (Stage stage : in.getStep().getStages())
		{
			if (stage.getBook() != null)
			{
				stage.getTiles().forEach(tile -> stageBooks.put(nodeAt(tile), stage.getBook()));
			}
			List<RequiredItems.Need> wanted = in.getRequired().getNeeds().stream()
				.filter(need -> stage.getWorn().contains(need.getName()) || stage.isTakesStepItems() && need.isStepItem())
				.collect(Collectors.toList());
			if (wanted.isEmpty() || stage.getTiles().isEmpty())
			{
				continue;
			}
			atStages.addAll(wanted);
			List<RequiredItems.Verdict> verdicts = new ArrayList<>();
			for (WorldPoint tile : stage.getTiles())
			{
				List<RequiredItems.Need> here = withPlaceUses(wanted, stage.getUses().getOrDefault(tile, List.of()));
				RequiredItems needs = in.getRequired().toBuilder().stash(RequiredItems.Stash.NONE).needs(here).build();
				RequiredItems.Verdict verdict = needs.judge(in.getPlayer(), bankedPlayer);
				stageNeeds.put(nodeAt(tile), verdict);
				verdicts.add(verdict);
			}
			if (verdicts.stream().allMatch(verdict -> !verdict.getMissing().isEmpty()))
			{
				missingEverywhere.addAll(verdicts);
			}
		}
		return atStages;
	}

	/** A need a place takes another item for is that item there (pure essence for fragments). */
	private static List<RequiredItems.Need> withPlaceUses(List<RequiredItems.Need> wanted, List<StepIndex.Alternative> uses)
	{
		List<RequiredItems.Need> here = new ArrayList<>();
		for (RequiredItems.Need need : wanted)
		{
			StepIndex.Alternative use = uses.stream().filter(u -> need.getRequirement().fulfilledBy(u.getInstead())).findFirst().orElse(null);
			if (use == null)
			{
				here.add(need);
				continue;
			}
			ItemRequirement[] groups = use.getAll().stream()
				.map(ids -> ItemRequirements.any(use.getName(), ids.stream().map(ItemRequirements::item).toArray(ItemRequirement[]::new)))
				.toArray(ItemRequirement[]::new);
			here.add(new RequiredItems.Need(use.getName(), ItemRequirements.all(use.getName(), groups), need.isStepItem(), false));
		}
		return here;
	}

	/** A stage none of whose places has its items, by its places' verdicts, each once: noted, and the plan stops. */
	List<RequiredItems.Verdict> stageVerdicts()
	{
		return List.copyOf(new LinkedHashSet<>(missingEverywhere));
	}

	/** The book a route must be on to arrive at this stage node, or null. */
	Book stageBook(int node)
	{
		return stageBooks.get(node);
	}

	/** A stage tile whose items are owned nowhere while another place's are: routes skip it. */
	boolean lacksItems(WorldPoint tile)
	{
		RequiredItems.Verdict verdict = stageNeeds.get(nodeAt(tile));
		return verdict != null && !verdict.getMissing().isEmpty();
	}

	/** A stage whose gear is in the bank can't be done before a bank stop; gear owned nowhere stops the plan instead. */
	boolean stageNeedsBank(int node, int bankState)
	{
		RequiredItems.Verdict verdict = stageNeeds.get(node);
		return verdict != null && !verdict.isCarried() && verdict.isBanked() && bankState != BANKED;
	}

	int[] stageWithdraw(int node, int bankState)
	{
		RequiredItems.Verdict verdict = stageNeeds.get(node);
		return verdict == null || verdict.isCarried() || bankState != BANKED ? new int[0]
			: verdict.getWithdraw().stream().mapToInt(ItemUse::getItemId).distinct().sorted().toArray();
	}

	/** Every stage's bank items, taken at the bank stop as a web's knife is. */
	int[] stageWithdraw()
	{
		return stageWithdraw;
	}

	boolean arrivesWithoutBank()
	{
		boolean depositsBook = depositsBookFirst && options[BANKED] != null;
		return !depositsBook && (required.isCarried() || !required.isBanked());
	}

	/** After a bank stop with nothing withdrawn yet, these are the only nodes worth walking to. */
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
			banked.changes.forEach((at, changes) -> changes.forEach(change -> bankItemUses[at] |= change.withdraw.length > 0));
		}
		return node < bankItemUses.length && bankItemUses[node];
	}

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

	/** A tile off the walk table gets an extra node, which only start legs and teleports reach. */
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

	/** Call once every node exists, after the targets are added. */
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

	LowerBound lowerBound(int[] targets)
	{
		return lowerBounds.computeIfAbsent(Arrays.toString(targets), k -> new LowerBound(this, targets));
	}

	boolean forbidden(int node)
	{
		return avoidsWilderness && classes[node].isWilderness();
	}

	private boolean allInWilderness(List<WorldPoint> tiles)
	{
		return !tiles.isEmpty() && tiles.stream().allMatch(tile -> OriginClass.of(tile, in.getNoTeleport()).isWilderness());
	}

	boolean changesAllowed(OriginClass origin)
	{
		return origin.allowsTeleports();
	}

	/** Not while the route is still in someone else's house: the furniture judged is the player's own. */
	boolean houseUsable(int node, int hasTransport)
	{
		return node != pohNode || hasTransport == 1 || !in.isForeignHouseStart();
	}

	/** The ring's Last-destination option goes there in one click, with no dialling. */
	boolean isLastFairyRing(CatalogRow landing)
	{
		String last = in.getPlayer().getLastFairyRing();
		return last != null && landing.getCode() != null && last.equals(landing.getCode().replace("-", ""));
	}

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
			return cape.halfTicks + CAPE_USE_PENALTY_HALF_TICKS;
		}
		return STRAND_PENALTY_HALF_TICKS;
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
				reachable.addAll(state.usableBooks());
			}
			for (List<Change> changes : o.changes.values())
			{
				for (Change located : changes)
				{
					if (located.change.getToggles() != null)
					{
						reachable.add(located.change.getToggles());
					}
					else if (state != null)
					{
						reachable.addAll(state.usableBooks());
					}
					reachable.add(Book.STANDARD);
				}
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

	static final class Teleport
	{
		CatalogRow row;
		int node;
		int halfTicks;
		Book book;
		int[] res;
		int[] amounts;
		int[] withdraw;
		List<ItemUse> withdrawUses;
		RowAvailability.Confidence confidence;
		boolean minigame;
		int maxWilderness;
	}

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

	final class Options
	{
		final List<Teleport> teleports = new ArrayList<>();
		final Map<Integer, Teleport> fairyEntries = new HashMap<>();
		final List<Teleport> fairyLandings = new ArrayList<>();
		/** Transports that start at a node: house furniture, spirit trees, Renu. */
		final Map<Integer, List<Teleport>> located = new HashMap<>();
		final Map<Integer, List<Change>> changes = new HashMap<>();
		Change cape;
		Change swap;

		Options(PlayerSnapshot player, int bankState)
		{
			PlayerSnapshot judgedAt = player.toBuilder().wildernessLevel(0).build();
			boolean carriesBook = player.getInventory().containsKey(BookScrolls.BOOK) || player.getWorn().containsKey(BookScrolls.BOOK);
			Map<String, Teleport> longestVariant = new LinkedHashMap<>();
			for (CatalogRow row : in.getCatalog().getTeleports())
			{
				if (in.getRefusedChanges().contains(row.getName()))
				{
					continue;
				}
				RowAvailability judged = in.getEngine().judge(row, judgedAt, in.getNowMinutes());
				if (!judged.isAvailable())
				{
					continue;
				}
				boolean bookStaysOut = in.getSettings().isScrollBookOutOfWilderness();
				boolean takenOut = BookScrolls.isTakenOutAtBank(row, bookStaysOut);
				boolean bankedFirst = !takenOut && bookStaysOut && carriesBook && BookScrolls.intoWilderness(row);
				if ((takenOut || bankedFirst) && bankState != BANKED)
				{
					continue;
				}
				Teleport tele = teleport(judged, player, bankState);
				tele.halfTicks += takenOut ? 2 * BookScrolls.TAKE_OUT_TICKS : bankedFirst ? 2 * BookScrolls.DEPOSIT_TICKS : 0;
				if (row.getOrigin() != null && row.getCategory() == Category.FAIRY_RING)
				{
					tele.node = nodeAt(row.getOrigin());
					tele.halfTicks += houseWalkHalfTicks(row.getRequirements(), player);
					fairyEntries.merge(tele.node, tele, (a, b) -> a.halfTicks <= b.halfTicks ? a : b);
				}
				else if (row.getOrigin() != null)
				{
					int from = nodeAt(row.getOrigin());
					tele.halfTicks += from == pohNode ? houseWalkHalfTicks(row.getRequirements(), player) : 0;
					located.computeIfAbsent(from, k -> new ArrayList<>()).add(tele);
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
			keepMostVersatileWithdraws(teleports);
			for (SpellbookChange change : in.getCatalog().getSpellbookChanges())
			{
				if (change.isInHouse() && pohNode == WalkTable.NO_NODE || in.getRefusedChanges().contains(change.getName()))
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
					c.node = change.isInHouse() ? pohNode : nodeAt(change.getTile());
					c.halfTicks += houseWalkHalfTicks(change.getRequirements(), player);
					changes.computeIfAbsent(c.node, k -> new ArrayList<>()).add(c);
				}
			}
		}

		/** Prefers e.g. the Max cape over a Construction cape that gives the same teleport. */
		private void keepMostVersatileWithdraws(List<Teleport> all)
		{
			Map<String, Teleport> kept = new HashMap<>();
			for (Teleport tele : all)
			{
				if (tele.withdraw.length > 0)
				{
					kept.merge(sameTeleportKey(tele), tele, (a, b) -> versatility(b.withdraw) > versatility(a.withdraw) ? b : a);
				}
			}
			all.removeIf(tele -> tele.withdraw.length > 0 && kept.get(sameTeleportKey(tele)) != tele);
		}

		private String sameTeleportKey(Teleport tele)
		{
			return tele.node + "|" + tele.halfTicks + "|" + tele.book;
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
			tele.halfTicks = 2 * (row.getDuration() + row.getMenuTicks() + LEG_OVERHEAD_TICKS)
				+ (tele.node != WalkTable.NO_NODE && tele.node == pohNode ? HOUSE_LOAD_HALF_TICKS : 0);
			tele.book = row.getRequirements().getBook();
			tele.confidence = judged.getConfidence();
			tele.minigame = row.getCategory() == Category.MINIGAME;
			tele.maxWilderness = row.getRequirements().getMaxWildernessLevel();
			Spending spending = spending(row.getSpends(), judged.getWitness(), player, bankState);
			tele.res = spending.res;
			tele.amounts = spending.amounts;
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

	private static int houseWalkHalfTicks(Requirements requirements, PlayerSnapshot player)
	{
		HouseNeed need = requirements.getHouse();
		if (need == null)
		{
			return 0;
		}
		HouseState house = player.getHouse();
		return 2 * house.walkTicks(need.getKind() == HouseNeed.Kind.NEXUS ? HouseState.NEXUS_OBJECTS : need.getIds());
	}

	private int versatility(int[] itemIds)
	{
		if (rowsPerItem == null)
		{
			rowsPerItem = new HashMap<>();
			for (CatalogRow row : in.getCatalog().getTeleports())
			{
				row.getRequirements().getItems().forEach(clause -> clause.getBranches().forEach(branch ->
					branch.getIds().forEach(id -> rowsPerItem.merge(id, 1, Integer::sum))));
			}
		}
		int rows = 0;
		for (int id : itemIds)
		{
			rows += rowsPerItem.getOrDefault(id, 0);
		}
		return rows;
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
	}

	private Spending spending(List<Spend> spends, List<ItemUse> witness, PlayerSnapshot player, int bankState)
	{
		Map<Integer, Integer> units = new LinkedHashMap<>();
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
						}
					}
					break;
				case CHARGE:
					for (ItemUse use : witness)
					{
						units.merge(resources.charge(use.getItemId(), player, bankState), 1, Integer::sum);
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
		return s;
	}

	static final class Resources
	{
		/** Assumed: no route uses one resource more often than this. */
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
