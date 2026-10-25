package com.cluehelper.route;

import com.cluehelper.SpellbookChanges;
import com.cluehelper.memory.StashMemory;
import com.cluehelper.player.Book;
import com.cluehelper.player.ItemUse;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.PositionRules;
import com.cluehelper.player.SpellbookState;
import com.cluehelper.step.Stage;
import com.cluehelper.step.StepIndex;
import com.cluehelper.transport.CatalogRow;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
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
	private static final int BANK_STOP_HALF_TICKS = 6;
	/** Per item stack withdrawn, worn or deposited at the bank. */
	static final int WITHDRAW_HALF_TICKS = 4;
	private static final int PIN_HALF_TICKS = 8;
	/** Ranking only: Magic and Max cape changes are limited per day. */
	static final int CAPE_USE_PENALTY_HALF_TICKS = 6;
	/** Ranking only: the route leaves you on another book with no way back left today. */
	private static final int STRAND_PENALTY_HALF_TICKS = 60;
	/** One more click when the withdraw list needs more slots than are free. */
	static final int DEPOSIT_FIRST_HALF_TICKS = 2;
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

	final PlanResources resources = new PlanResources();
	final TransportOptions[] options = new TransportOptions[2];
	private final WalkGates[] gates = new WalkGates[2];
	/** Books a route may change to; always holds the start book. */
	final Set<Book> expanded = EnumSet.noneOf(Book.class);
	final Book startBook;
	final Book homeBook;
	final boolean startPending;
	private final boolean targetInWilderness;
	/** Every target of the step, or of one of its stages, is in the Wilderness: avoiding it would leave no way there. */
	final boolean onlyInWilderness;
	final boolean avoidsWilderness;
	/** The master scroll book is carried and stays out of the Wilderness, where the target is: a bank stop comes first. */
	private final boolean depositsBookFirst;
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
			&& (carrier.carried(BookScrolls.BOOK) > 0);
		options[NOT_BANKED] = new TransportOptions(this, in.getPlayer(), NOT_BANKED);
		if (in.getSettings().isBankEdge() && in.getBanked() != null)
		{
			options[BANKED] = new TransportOptions(this, in.getBanked(), BANKED);
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
				RequiredItems needs = in.getRequired().toBuilder().stash(StashMemory.State.NONE).needs(here).build();
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
		return verdict != null && verdict.isFromBank() && bankState != BANKED;
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
		return !depositsBook && !required.isFromBank();
	}

	/** After a bank stop with nothing withdrawn yet, these are the only nodes worth walking to. */
	boolean usesBankItems(int node)
	{
		TransportOptions banked = options[BANKED];
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

	/** Changes are a setting, and a teleport must be able to follow. */
	boolean changesAllowed(OriginClass origin)
	{
		return in.getSettings().getSpellbookChanges() == SpellbookChanges.ALL && origin.allowsTeleports();
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
		TransportOptions.Change cape = options[bankState].cape;
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
		TransportOptions widest = options[options[BANKED] != null ? BANKED : NOT_BANKED];
		Set<Book> reachable = EnumSet.noneOf(Book.class);
		SpellbookState state = in.getPlayer().getSpellbook();
		for (TransportOptions o : options)
		{
			if (o == null)
			{
				continue;
			}
			if (o.cape != null && state != null)
			{
				reachable.addAll(state.usableBooks());
			}
			for (List<TransportOptions.Change> changes : o.changes.values())
			{
				for (TransportOptions.Change located : changes)
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

	/** How many catalog teleports use the items. */
	int versatility(int[] itemIds)
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
}
