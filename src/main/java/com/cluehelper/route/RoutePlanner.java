package com.cluehelper.route;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.hotcold.ProbeTarget;
import com.cluehelper.link.ClueLink;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.*;
import com.cluehelper.poh.*;
import com.cluehelper.step.*;
import com.cluehelper.transport.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;

/**
 * Client thread, except the plans themselves, which run on the executor through {@link PlannerRunner}. Re-plans only
 * when a {@link Trigger} input changes, never per tile.
 */
@Slf4j
@Singleton
public class RoutePlanner
{
	static final int COMMIT_MARGIN_TICKS = 5;
	/** A Spellbook Swap's free cast stays pending this long after varbit 3617 turns 1. */
	private static final int SWAP_WINDOW_TICKS = 200;
	private static final int NOT_REFUSED = -1;
	private static final int TARGET_WALK_BUCKET_TILES = 2 * COMMIT_MARGIN_TICKS;
	/** The guide's landing radius: some teleports land anywhere in a small area. */
	private static final int LANDED_TILES = 10;
	private static final int POCKET_TILES = 3;
	private static final int NO_TARGET_NEAR = -1;
	private static final long NO_SCENE = Long.MIN_VALUE;

	@Inject
	private Client client;
	@Inject
	private ClueLink clueLink;
	@Inject
	private PlayerModel player;
	@Inject
	private AvailabilityService availability;
	@Inject
	private CatalogData catalogData;
	@Inject
	private WalkData walkData;
	@Inject
	private PlannerRunner runner;
	/** The walk from the player's tile, searched apart so it never supersedes a plan. */
	private PlannerRunner startRunner;
	@Inject
	private ClueHelperConfig config;
	@Inject
	private BankMemory bankMemory;
	@Inject
	private StepNeeds stepNeeds;
	@Inject
	private PohService poh;
	@Inject
	private ProbeTarget probeTarget;
	@Inject
	private StageTarget stageTarget;
	@Inject
	private SceneDoors doors;
	private final List<Consumer<Plan>> listeners = new CopyOnWriteArrayList<>();
	private final List<BiConsumer<Plan, PlanInput>> inputListeners = new CopyOnWriteArrayList<>();

	private volatile WalkData.Loaded walk;
	private volatile Catalog catalog;
	private long gatesVersion = -1;
	private boolean[] rowOpen;
	private WorldPoint lastTile;
	/** After a teleport the scene loads a tick after the tile moves: a walk over the old scene must be redone. */
	private long lastSceneBase = NO_SCENE;
	@Getter
	private volatile PlanStart start;
	private volatile int nearestNode = WalkTable.NO_NODE;
	private int targetWalkBucket = NO_TARGET_NEAR;
	private Trigger lastTrigger;
	@Getter
	private volatile Plan plan = Plan.NONE;
	@Getter
	private volatile Route committed;
	private String committedStep;
	/** The step's targets when it was committed: a hot/cold step keeps its key while its target moves. */
	private List<WorldPoint> committedTargets = List.of();
	/** The committed walk was kept once although the last plan didn't offer it. */
	private boolean walkHeld;
	private int swapCheckAtCapeRefusal = NOT_REFUSED;
	private final Set<String> refusedChanges = new HashSet<>();

	@Inject
	void init(ScheduledExecutorService executor, ClientThread clientThread)
	{
		startRunner = new PlannerRunner(executor, clientThread);
	}

	/** The guide takes such a scroll out at the bank as the plan does. */
	public boolean keepsScrollBookOutOfWilderness()
	{
		return config.scrollBookOutOfWilderness();
	}

	public void start()
	{
		catalogData.get().thenAccept(loaded -> catalog = loaded);
		walkData.get().thenAccept(loaded ->
		{
			if (loaded != null)
			{
				watchGateVars(loaded.getTable());
				walk = loaded;
			}
		});
	}

	public void stop()
	{
		runner.cancelAll();
		startRunner.cancelAll();
		plan = Plan.NONE;
		committed = null;
		committedStep = null;
		committedTargets = List.of();
		walkHeld = false;
		lastTrigger = null;
		lastTile = null;
		start = null;
	}

	/** Listeners run on the client thread with each new plan. */
	public Runnable addListener(Consumer<Plan> listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** Like {@link #addListener}, with what each plan was planned from: the dev launcher records slow ones. */
	public Runnable addInputListener(BiConsumer<Plan, PlanInput> listener)
	{
		inputListeners.add(listener);
		return () -> inputListeners.remove(listener);
	}

	/** The step to travel to now: the clue's step, with a hot/cold probe as its target and the stages already done dropped. */
	public Step travelStep()
	{
		return stageTarget.travelStep(probeTarget.travelStep(clueLink.getCurrentStep()));
	}

	/** Call after the player model's and availability's GameTick handlers. */
	public void onGameTick()
	{
		refresh();
	}

	/** The start walk from a new tile first, then a plan once any of its inputs changed. */
	private void refresh()
	{
		Step step = travelStep();
		PlayerSnapshot snapshot = player.getSnapshot();
		RuneModel runes = player.getRuneModel();
		WalkData.Loaded data = walk;
		Catalog loadedCatalog = catalog;
		boolean dormant = snapshot != null && snapshot.getDormantReason() != null;
		if (step == null || snapshot == null || runes == null || data == null || loadedCatalog == null || dormant)
		{
			if ((step == null || dormant) && plan != Plan.NONE)
			{
				stop();
				listeners.forEach(listener -> listener.accept(Plan.NONE));
			}
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		forgetCapeRefusalOnceCheckMoved(snapshot);
		snapshot = withRefusals(snapshot);
		updateStart(data, snapshot, runes, step);
		if (start == null)
		{
			return;
		}
		AvailabilitySet carried = availability.getCarried();
		RequiredItems required = stepNeeds.of(step);
		Trigger trigger = new Trigger(step, snapshot.getVersion(), carried.getSnapshotVersion(), carried.getValidUntilMinute(),
			nearestNode, targetWalkBucket, start.getKind(), snapshot.getSpellbook(), PlannerSettings.from(config, snapshot.getAccountType()),
			Set.copyOf(refusedChanges), bankMemory.getVersion(), bankMemory.isBankOpen(), required, isForeignHouse());
		if (trigger.equals(lastTrigger))
		{
			return;
		}
		lastTrigger = trigger;
		PlanInput input = input(step, snapshot, runes, data, loadedCatalog, required);
		PlanInput followingInput = input.toBuilder().followedFirstTransport(followedFirstTransport(step)).build();
		runner.submit(() -> Planner.withoutAlternatives(followingInput), planned -> onPlanned(planned, followingInput));
	}

	/**
	 * Client thread. What a plan for {@code step} would get now, for searches of other planners; null until the
	 * first plan's inputs are all loaded.
	 */
	public PlanInput inputFor(Step step, RequiredItems required)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		RuneModel runes = player.getRuneModel();
		WalkData.Loaded data = walk;
		Catalog loadedCatalog = catalog;
		if (step == null || snapshot == null || runes == null || data == null || loadedCatalog == null || start == null)
		{
			return null;
		}
		return input(step, withRefusals(snapshot), runes, data, loadedCatalog, required);
	}

	/** Loaded walk data; null until it is. */
	public WalkData.Loaded getWalk()
	{
		return walk;
	}

	/** Client thread. The walk rows the player can take now, fixed for a search on another thread. */
	public IntPredicate openRows()
	{
		boolean[] open = rowOpen;
		return row -> open != null && open[row];
	}

	private PlanInput input(Step step, PlayerSnapshot snapshot, RuneModel runes, WalkData.Loaded data, Catalog loadedCatalog,
		RequiredItems required)
	{
		PlayerSnapshot banked = availability.getBankedSnapshot();
		return PlanInput.builder()
			.step(step)
			.player(snapshot)
			.banked(banked == null ? null : withRefusals(banked))
			.pinPending(bankMemory.isPinPending())
			.atOpenBank(bankMemory.isBankOpen())
			.required(required)
			.engine(new AvailabilityEngine(runes, id -> "item " + id))
			.nowMinutes(AvailabilityService.nowMinutes())
			.catalog(loadedCatalog)
			.table(data.getTable())
			.noTeleport(data.getNoTeleport())
			.start(start)
			.settings(PlannerSettings.from(config, snapshot.getAccountType()))
			.revertTicksLeft(revertTicksLeft())
			.refusedChanges(Set.copyOf(refusedChanges))
			.foreignHouseStart(isForeignHouse())
			.build();
	}

	private boolean isForeignHouse()
	{
		return poh.isInHouse() && poh.getOwnership() == HouseOwnership.Ownership.FOREIGN;
	}

	/** Client thread. The game refused a cape change for today's limit, whatever SB_SWAP_CHECK says. */
	public void onCapeChangesRefused()
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		Integer used = snapshot == null ? null : snapshot.varbit(VarbitID.SB_SWAP_CHECK);
		swapCheckAtCapeRefusal = used == null ? 0 : used;
		lastTrigger = null;
		log.debug("cape change refused at 4816 = {}", swapCheckAtCapeRefusal);
	}

	/**
	 * Client thread. No var tells of some refusals (the Music cape's "untrimmed cape" rule), so the row isn't offered
	 * again this session.
	 */
	public void onRowRefused(String row)
	{
		if (refusedChanges.add(row))
		{
			lastTrigger = null;
			log.debug("refused this session: {}", row);
		}
	}

	/** The refused cape change counts until SB_SWAP_CHECK moves: the day's limit has reset by then. */
	private void forgetCapeRefusalOnceCheckMoved(PlayerSnapshot snapshot)
	{
		Integer used = snapshot.varbit(VarbitID.SB_SWAP_CHECK);
		if (swapCheckAtCapeRefusal != NOT_REFUSED && used != null && used != swapCheckAtCapeRefusal)
		{
			swapCheckAtCapeRefusal = NOT_REFUSED;
		}
	}

	/** After a cape refusal, no cape uses are left. */
	private PlayerSnapshot withRefusals(PlayerSnapshot snapshot)
	{
		if (swapCheckAtCapeRefusal == NOT_REFUSED)
		{
			return snapshot;
		}
		Map<Integer, Integer> varbits = new HashMap<>(snapshot.getVarbits());
		varbits.put(VarbitID.SB_SWAP_CHECK, SpellbookState.CAPE_CHANGES_PER_DAY);
		SpellbookState book = snapshot.getSpellbook();
		SpellbookState noCapeUses = book == null ? null : new SpellbookState(book.getBook(), book.isPendingRevert(), 0,
			book.isArceuusUnlocked(), book.isAncientAccess(), book.isLunarAccess(), book.isSwapAccess());
		return snapshot.toBuilder().varbits(varbits).spellbook(noCapeUses).build();
	}

	private void updateStart(WalkData.Loaded data, PlayerSnapshot snapshot, RuneModel runes, Step step)
	{
		Position position = player.getPosition();
		WalkTable table = data.getTable();
		if (position.getKind() != Position.Kind.WORLD)
		{
			startRunner.cancelAll();
		}
		if (position.getKind() == Position.Kind.POH && table.node(PositionRules.POH_NODE) != WalkTable.NO_NODE)
		{
			start = PlanStart.atNode(table, table.node(PositionRules.POH_NODE));
			nearestNode = start.getNode();
			lastTile = null;
			return;
		}
		if (position.getKind() != Position.Kind.WORLD)
		{
			start = PlanStart.teleportOnly(position.getTile());
			nearestNode = WalkTable.NO_NODE;
			lastTile = null;
			return;
		}
		WorldView view = client.getTopLevelWorldView();
		long sceneBase = sceneBase(view);
		boolean sceneReloaded = sceneBase != lastSceneBase;
		if (position.getTile().equals(lastTile) && (start != null || startRunner.isWorking()) && !sceneReloaded)
		{
			return;
		}
		lastTile = position.getTile();
		lastSceneBase = sceneBase;
		refreshGates(table, snapshot, runes);
		LoadedScene scene = doors.withDoorsOpen(view, LoadedScene.capture(view), table);
		Ground ground = Ground.world(data.getMap(), scene, table, openRows());
		WorldPoint tile = position.getTile();
		List<WorldPoint> targets = step.getTargets();
		startRunner.submit(() -> WalkCosts.from(ground, tile), walk -> walked(table, tile, targets, walk));
	}

	/** Client thread: the walk from the player's tile is in, and the plan goes ahead now rather than a tick later. */
	private void walked(WalkTable table, WorldPoint tile, List<WorldPoint> targets, WalkCosts walk)
	{
		if (isMidObstacle(walk) && start != null)
		{
			retryNextTick();
			return;
		}
		int nearestHere = nearest(table, walk);
		int targetWalk = targets.stream().mapToInt(walk::cost).min().orElse(WalkCosts.UNREACHED);
		boolean targetNear = targetWalk != WalkCosts.UNREACHED;
		if (isInPocket(nearestHere, targetNear, tile))
		{
			retryNextTick();
			return;
		}
		nearestNode = nearestHere;
		targetWalkBucket = targetNear ? targetWalk / TARGET_WALK_BUCKET_TILES : NO_TARGET_NEAR;
		start = nearestNode != WalkTable.NO_NODE || targetNear ? PlanStart.scene(tile, walk)
			: PlanStart.teleportOnly(tile, walk);
		refresh();
	}
	private static long sceneBase(WorldView view)
	{
		return view == null ? NO_SCENE : (long) view.getBaseX() << 32 | view.getBaseY() & 0xFFFFFFFFL;
	}

	/** Each new snapshot judges the walk gates and the webs again. */
	private void refreshGates(WalkTable table, PlayerSnapshot snapshot, RuneModel runes)
	{
		if (gatesVersion == snapshot.getVersion())
		{
			return;
		}
		WalkGates gates = new WalkGates(table, snapshot, new AvailabilityEngine(runes, id -> ""), AvailabilityService.nowMinutes());
		rowOpen = gates.rowOpen();
		doors.playerChanged(snapshot);
		gatesVersion = snapshot.getVersion();
	}

	/**
	 * Collision flags still loading after a reload, or the player mid-obstacle on a blocked tile (stepping stones, a
	 * log): the walk reaches only the player's tile.
	 */
	private static boolean isMidObstacle(WalkCosts walk)
	{
		return walk.reachedTiles() <= 1;
	}

	/** Mid-obstacle in a pocket with no node (south of Kaleb's, anim 741), a few tiles from a start that had nodes. */
	private boolean isInPocket(int nearestHere, boolean targetInScene, WorldPoint tile)
	{
		return nearestHere == WalkTable.NO_NODE && !targetInScene && start != null && start.getKind() == PlanStart.Kind.SCENE
			&& start.getTile() != null && start.getTile().distanceTo2D(tile) <= POCKET_TILES;
	}

	/** The last start stands until the next tick looks again. */
	private void retryNextTick()
	{
		lastTile = null;
		lastSceneBase = NO_SCENE;
	}

	private static int nearest(WalkTable table, WalkCosts walk)
	{
		int best = WalkTable.NO_NODE;
		int bestCost = WalkCosts.UNREACHED;
		for (int node = 0; node < table.nodeCount(); node++)
		{
			int cost = walk.cost(table.placed(node));
			if (cost < bestCost)
			{
				best = node;
				bestCost = cost;
			}
		}
		return best;
	}

	private int revertTicksLeft()
	{
		int since = player.getPendingRevertSinceTick();
		return since == PlayerModel.UNKNOWN_TICK ? PlanInput.NO_REVERT_WINDOW
			: Math.max(PlanInput.NO_REVERT_WINDOW, SWAP_WINDOW_TICKS - (client.getTickCount() - since));
	}

	private String followedFirstTransport(Step step)
	{
		return committed != null && step.getKey().equals(committedStep) ? committed.getFirstTransportKey() : null;
	}

	private void onPlanned(Plan planned, PlanInput input)
	{
		plan = planned;
		committed = commit(planned, input.getStep().getTargets());
		committedStep = planned.getStepKey();
		committedTargets = input.getStep().getTargets();
		listeners.forEach(listener -> listener.accept(planned));
		inputListeners.forEach(listener -> listener.accept(planned, input));
	}

	private Route commit(Plan planned, List<WorldPoint> targets)
	{
		Route best = planned.getBest();
		Route current = committed;
		boolean sameGoal = Objects.equals(committedStep, planned.getStepKey()) && Objects.equals(committedTargets, targets);
		if (best == null || current == null || !sameGoal)
		{
			walkHeld = false;
			return best;
		}
		Route same = offered(planned, current.getIdentity());
		if (same == null)
		{
			return holdBlockedWalk(current, best);
		}
		walkHeld = false;
		if (best.getTicks() <= same.getTicks() - COMMIT_MARGIN_TICKS || landsWhereItStarts(same))
		{
			return best;
		}
		return same;
	}

	/**
	 * A walk the loaded scene suddenly blocks is kept for one plan: a gate's collision lags a tick behind it swinging
	 * open (King Lathas' camp). Only a walk the next plan still doesn't offer gives way.
	 */
	private Route holdBlockedWalk(Route walk, Route best)
	{
		boolean hold = !walkHeld && walk.getFirstTransport() == null && !walk.isBanked() && isAlong(walk);
		walkHeld = hold;
		return hold ? walk : best;
	}

	/** The player is still on the walk's way, not teleported off it (back to the Crafting Guild). */
	private boolean isAlong(Route walk)
	{
		PlanStart from = start;
		WorldPoint at = from == null ? null : from.getTile();
		if (at == null || walk.getLegs().isEmpty())
		{
			return false;
		}
		Leg leg = walk.getLegs().get(0);
		WorldPoint end = walk.getLegs().get(walk.getLegs().size() - 1).getTo();
		return at.getPlane() == end.getPlane() && at.distanceTo2D(end) <= leg.getFrom().distanceTo2D(end) + LANDED_TILES;
	}

	/**
	 * Right after a teleport the followed route is still offered from the landing, teleporting to where the player
	 * already stands; keeping it would send the guide back to the teleport.
	 */
	private boolean landsWhereItStarts(Route route)
	{
		Leg first = route.getFirstTransport();
		PlanStart from = start;
		return first != null && from != null && from.getTile() != null && first.getTo() != null
			&& first.getTo().getPlane() == from.getTile().getPlane() && first.getTo().distanceTo2D(from.getTile()) <= LANDED_TILES;
	}

	private static Route offered(Plan planned, String identity)
	{
		for (Route route : routesOf(planned))
		{
			if (route != null && route.getIdentity().equals(identity))
			{
				return route;
			}
		}
		return null;
	}

	private static List<Route> routesOf(Plan planned)
	{
		List<Route> routes = new ArrayList<>();
		routes.add(planned.getBest());
		routes.add(planned.getDirect());
		routes.add(planned.getSameBook());
		routes.addAll(planned.getAlternatives());
		routes.add(planned.getFollowed());
		planned.getCandidates().forEach(candidate -> routes.add(candidate.getRoute()));
		return routes;
	}

	private void watchGateVars(WalkTable table)
	{
		Set<Integer> varbits = new HashSet<>();
		Set<Integer> varps = new HashSet<>();
		for (Requirements gate : table.getGates())
		{
			for (VarCheck check : gate.getVars())
			{
				(check.getKind() == VarCheck.Kind.VARBIT ? varbits : varps).add(check.getId());
			}
		}
		player.watch(varbits, varps);
	}

	/** Walking changes it only through the nearest node or the walk to a target in the scene. */
	@Value
	private static class Trigger
	{
		Step step;
		long snapshotVersion;
		long carriedVersion;
		long carriedValidUntil;
		int nearestNode;
		int targetWalkBucket;
		PlanStart.Kind startKind;
		SpellbookState spellbook;
		PlannerSettings settings;
		Set<String> refusedChanges;
		long bankVersion;
		boolean atOpenBank;
		RequiredItems required;
		boolean foreignHouse;
	}
}
