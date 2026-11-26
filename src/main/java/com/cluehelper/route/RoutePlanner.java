package com.cluehelper.route;

import com.cluehelper.*;
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
import java.util.stream.IntStream;
import javax.inject.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;

/**
 * Client thread, except the plans themselves, which run on the executor through {@link PlannerRunner}. Re-plans only
 * when a {@link Trigger} input changes, never per tile.
 */
@Singleton
public class RoutePlanner
{
	/** Once acted on, the followed route gives way only to one that saves a quarter of its time left, and at least this. */
	private static final int MIN_SWITCH_TICKS = 2;
	private static final int SWITCH_SHARE = 4;
	/** A Spellbook Swap's free cast stays pending this long after varbit 3617 turns 1. */
	private static final int SWAP_WINDOW_TICKS = 200;
	private static final int TARGET_WALK_BUCKET_TILES = 10;
	/** The guide's landing radius: some teleports land anywhere in a small area. */
	private static final int LANDED_TILES = 10;
	private static final int POCKET_TILES = 3;
	private static final int NO_TARGET_NEAR = -1;
	private static final long NO_SCENE = Long.MIN_VALUE;

	@Inject
	Client client;
	@Inject
	ClueLink clueLink;
	@Inject
	PlayerModel player;
	@Inject
	AvailabilityService availability;
	@Inject
	BundledData bundledData;
	@Inject
	PlannerRunner runner;
	/** The walk from the player's tile, searched apart so it never supersedes a plan. */
	PlannerRunner startRunner;
	@Inject
	ClueHelperConfig config;
	@Inject
	BankMemory bankMemory;
	@Inject
	StepNeeds stepNeeds;
	@Inject
	PohService poh;
	@Inject
	ProbeTarget probeTarget;
	@Inject
	StageTarget stageTarget;
	@Inject
	SceneDoors doors;
	final List<Consumer<Plan>> listeners = new CopyOnWriteArrayList<>();
	final List<BiConsumer<Plan, PlanInput>> inputListeners = new CopyOnWriteArrayList<>();

	/** Loaded walk data; null until it is. */
	@Getter
	volatile WalkData walk;
	volatile Catalog catalog;
	long gatesVersion = -1;
	boolean[] rowOpen;
	WorldPoint lastTile;
	/** After a teleport the scene loads a tick after the tile moves: a walk over the old scene must be redone. */
	long lastSceneBase = NO_SCENE;
	@Getter
	volatile PlanStart start;
	volatile int nearestNode = WalkTable.NO_NODE;
	int targetWalkBucket = NO_TARGET_NEAR;
	Trigger lastTrigger;
	volatile Plan plan = Plan.NONE;
	volatile Route committed;
	/** The plan is for {@link ClueLink#getAheadStep()}: listeners and getters see none until core shows that step. */
	volatile boolean ahead;
	String committedStep;
	/** The step's targets when it was committed: a hot/cold step keeps its key while its target moves. */
	List<WorldPoint> committedTargets = List.of();
	/**
	 * The player walked along the committed route, took its first teleport or withdrew its items: from then on it is kept
	 * until it breaks or a route saves enough of its time. Until then a better route replaces it freely.
	 */
	boolean acted;
	/** Where the committed route was planned from and what the player carried then, to tell when they act on it. */
	WorldPoint committedFrom;
	PlayerSnapshot committedWith;
	/** The committed walk was kept once although the last plan didn't offer it. */
	boolean walkHeld;
	final Set<String> refusedChanges = new HashSet<>();
	/** A teleport under way: its item or runes are already spent, so a plan now would fetch them again. */
	BooleanSupplier travelling = () -> false;

	@Inject
	void init(BackgroundThread executor, ClientThread clientThread)
	{
		startRunner = new PlannerRunner(executor, clientThread);
	}

	/** The walk table's exit a walk leg goes through within so many tiles of walking; null for none, or before it loads. */
	public Exit exitBetween(Leg leg, int tiles)
	{
		WalkData loaded = walk;
		return loaded == null ? null : loaded.getTable().exitBetween(leg.getFrom(), leg.getTo(), tiles);
	}

	/** The guide takes such a scroll out at the bank as the plan does. */
	public boolean keepsScrollBookOutOfWilderness()
	{
		return config.scrollBookOutOfWilderness();
	}

	public void start()
	{
		bundledData.getCatalog().thenAccept(loaded -> catalog = loaded);
		bundledData.getWalk().thenAccept(loaded ->
		{
			if (loaded != null)
			{
				player.watch(loaded.getTable().getWatchedVarbits(), loaded.getTable().getWatchedVarps());
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
		acted = false;
		committedFrom = null;
		committedWith = null;
		walkHeld = false;
		lastTrigger = null;
		lastTile = null;
		start = null;
		ahead = false;
	}

	public Plan getPlan()
	{
		return ahead ? Plan.NONE : plan;
	}

	public Route getCommitted()
	{
		return ahead ? null : committed;
	}

	/** Client thread. No plan is made while {@code travelling} holds; the next tick after it plans as usual. */
	public void holdWhile(BooleanSupplier travelling)
	{
		this.travelling = travelling;
	}

	/** Listeners run on the client thread with each new plan. */
	public Runnable addListener(Consumer<Plan> listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** Like {@link #addListener}, with what each plan was planned from, and told of the plans made ahead too. */
	public Runnable addInputListener(BiConsumer<Plan, PlanInput> listener)
	{
		inputListeners.add(listener);
		return () -> inputListeners.remove(listener);
	}

	/**
	 * The step to travel to now: the clue's step, with a hot/cold probe as its target and the stages already done dropped;
	 * null while core shows no clue.
	 */
	public Step travelStep()
	{
		return travelStep(clueLink.getCurrentStep());
	}

	private Step travelStep(Step step)
	{
		return stageTarget.travelStep(probeTarget.travelStep(step));
	}

	/** Call after the player model's and availability's GameTick handlers. */
	public void onGameTick()
	{
		refresh();
	}

	/**
	 * A teleport's scene loads after the tick that moved the player, so the walk from the new tile is redone as soon as it
	 * has loaded rather than on the next tick. Skipped while the player model still holds the tile from before the jump.
	 */
	public void onGameStateChanged(GameStateChanged event)
	{
		Player local = client.getLocalPlayer();
		if (event.getGameState() == GameState.LOGGED_IN && local != null && local.getWorldLocation().equals(player.getPosition().getTile()))
		{
			refresh();
		}
	}

	/** Client thread. Plans now rather than on the next tick, after something off the tick changed {@link #travelStep()}. */
	public void onTravelStepChanged()
	{
		refresh();
	}

	/** The start walk from a new tile first, then a plan once any of its inputs changed. */
	private void refresh()
	{
		Step shown = travelStep();
		Step step = shown != null ? shown : travelStep(clueLink.getAheadStep());
		PlayerSnapshot snapshot = player.getSnapshot();
		RuneModel runes = player.getRuneModel();
		WalkData data = walk;
		boolean dormant = snapshot != null && snapshot.getDormantReason() != null;
		if (step == null || snapshot == null || runes == null || data == null || catalog == null || dormant)
		{
			if ((step == null || dormant) && plan != Plan.NONE)
			{
				dropPlan();
			}
			return;
		}
		if (shown == null && !ahead && (plan != Plan.NONE || runner.isWorking()))
		{
			dropPlan();
		}
		if (client.getGameState() != GameState.LOGGED_IN || travelling.getAsBoolean())
		{
			return;
		}
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
		boolean wasAhead = ahead;
		ahead = shown == null;
		if (wasAhead && shown != null)
		{
			reveal(shown);
		}
		if (trigger.equals(lastTrigger))
		{
			return;
		}
		lastTrigger = trigger;
		PlanInput input = input(step, snapshot, runes, required);
		Route followed = followed(step);
		acted = acted || followed != null && isActedOn(followed, snapshot);
		followed = acted ? followed : null;
		PlanInput followingInput = followed == null ? input
			: input.toBuilder().followedFirstTransport(followed.getFirstTransportKey()).followedIdentity(followed.getIdentity()).build();
		runner.submit(() -> Planner.plan(followingInput), planned -> onPlanned(planned, followingInput));
	}

	/**
	 * Core now shows a step: the plan made ahead for it is the guide's at once. Whatever the read changed for the planner
	 * still starts a new plan, which replaces it when it comes in.
	 */
	private void reveal(Step shown)
	{
		if (shown.getKey().equals(plan.getStepKey()))
		{
			listeners.forEach(listener -> listener.accept(plan));
		}
		else
		{
			plan = Plan.NONE;
		}
	}

	/** Listeners hear of it only if they were shown the plan. */
	private void dropPlan()
	{
		boolean shown = !ahead;
		stop();
		if (shown)
		{
			listeners.forEach(listener -> listener.accept(Plan.NONE));
		}
	}

	/**
	 * Client thread. What a plan for {@code step} would get now, for searches of other planners; null until the
	 * first plan's inputs are all loaded.
	 */
	public PlanInput inputFor(Step step, RequiredItems required)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		RuneModel runes = player.getRuneModel();
		if (step == null || snapshot == null || runes == null || walk == null || catalog == null || start == null)
		{
			return null;
		}
		return input(step, snapshot, runes, required);
	}

	/** Client thread. The walk rows the player can take now, fixed for a search on another thread. */
	public IntPredicate openRows()
	{
		boolean[] open = rowOpen;
		return row -> open != null && open[row];
	}

	/** Only once the walk data and the catalog are loaded, which are never unloaded. */
	private PlanInput input(Step step, PlayerSnapshot snapshot, RuneModel runes, RequiredItems required)
	{
		PlayerSnapshot banked = availability.getBankedSnapshot();
		return PlanInput.builder()
			.step(step)
			.player(snapshot)
			.banked(banked)
			.pinPending(bankMemory.isPinPending())
			.atOpenBank(bankMemory.isBankOpen())
			.required(required)
			.engine(new AvailabilityEngine(runes, availability::itemName))
			.nowMinutes(AvailabilityService.nowMinutes())
			.catalog(catalog)
			.table(walk.getTable())
			.noTeleport(walk.getNoTeleport().now(client::getVarbitValue))
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

	/**
	 * Client thread. The game refused a cape change for today's limit, whatever SB_SWAP_CHECK says, so no cape change is
	 * offered again this session.
	 */
	public void onCapeChangesRefused()
	{
		catalog.getSpellbookChanges().stream()
			.filter(change -> change.getSpends().stream().anyMatch(spend -> spend.getKind() == Spend.Kind.CAPE_USE))
			.forEach(change -> onRowRefused(change.getName()));
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
		}
	}

	private void updateStart(WalkData data, PlayerSnapshot snapshot, RuneModel runes, Step step)
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
		if (isInPocket(table, nearestHere, targetNear, tile))
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
		doors.setCanSlash(gates.canSlash());
		doors.setCarried(snapshot.carriedIds());
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

	/**
	 * Mid-obstacle in a pocket with no node, a few tiles from a start that had nodes, or anywhere along a crossing that
	 * starts beside it: a rope pulls the player across the water tile by tile.
	 */
	private boolean isInPocket(WalkTable table, int nearestHere, boolean targetInScene, WorldPoint tile)
	{
		if (nearestHere != WalkTable.NO_NODE || targetInScene || start == null || start.getKind() != PlanStart.Kind.SCENE
			|| start.getTile() == null)
		{
			return false;
		}
		WorldPoint from = start.getTile();
		return from.distanceTo2D(tile) <= POCKET_TILES || IntStream.range(0, table.rowCount())
			.anyMatch(row -> isAlongCrossing(table.rowOrigin(row), table.rowDest(row), from, tile));
	}

	private static boolean isAlongCrossing(WorldPoint origin, WorldPoint dest, WorldPoint from, WorldPoint tile)
	{
		return origin.getPlane() == tile.getPlane() && dest.getPlane() == tile.getPlane() && origin.distanceTo2D(from) <= POCKET_TILES
			&& origin.distanceTo2D(tile) + tile.distanceTo2D(dest) <= origin.distanceTo2D(dest) + 1;
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

	private Route followed(Step step)
	{
		return committed != null && step.getKey().equals(committedStep) ? committed : null;
	}

	private void onPlanned(Plan planned, PlanInput input)
	{
		plan = planned;
		committed = commit(planned, input.getStep().getTargets());
		if (!acted)
		{
			committedFrom = input.getStart().getTile();
			committedWith = input.getPlayer();
		}
		committedStep = planned.getStepKey();
		committedTargets = input.getStep().getTargets();
		if (!ahead)
		{
			listeners.forEach(listener -> listener.accept(planned));
		}
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
			acted = acted && sameGoal;
			return best;
		}
		if (!acted)
		{
			return best;
		}
		Route same = offered(planned, current.getIdentity());
		if (same == null)
		{
			return holdBlockedWalk(current, best);
		}
		walkHeld = false;
		if (savesEnough(same.getTicks(), best.getTicks()) || landsWhereItStarts(same))
		{
			return best;
		}
		return same;
	}

	static boolean savesEnough(int followedTicks, int bestTicks)
	{
		int saved = followedTicks - bestTicks;
		return saved >= MIN_SWITCH_TICKS && saved * SWITCH_SHARE >= followedTicks;
	}

	/** The search limit for the followed route: past it, the best route saves enough of its time to replace it. */
	static int keptUpToHalfTicks(int bestHalfTicks)
	{
		return Math.max(bestHalfTicks + 2 * MIN_SWITCH_TICKS, bestHalfTicks * SWITCH_SHARE / (SWITCH_SHARE - 1));
	}

	/**
	 * The player walked off where the route was planned from toward the end of its first leg, landed from its first
	 * teleport, or carries more of an item its bank stop withdraws.
	 */
	private boolean isActedOn(Route route, PlayerSnapshot now)
	{
		PlanStart from = start;
		WorldPoint at = from == null ? null : from.getTile();
		return at != null && (walkedAlong(route, at) || landsWhereItStarts(route)) || withdrewFor(route, now);
	}

	private boolean walkedAlong(Route route, WorldPoint at)
	{
		Leg first = route.getLegs().isEmpty() ? null : route.getLegs().get(0);
		WorldPoint end = first == null || first.getKind() != Leg.Kind.WALK ? null : first.getTo();
		return end != null && committedFrom != null && !at.equals(committedFrom)
			&& at.distanceTo2D(end) < committedFrom.distanceTo2D(end);
	}

	private boolean withdrewFor(Route route, PlayerSnapshot now)
	{
		PlayerSnapshot before = committedWith;
		return before != null && route.getWithdraw().stream().anyMatch(use -> now.carried(use.getItemId()) > before.carried(use.getItemId()));
	}

	/**
	 * A walk the loaded scene suddenly blocks is kept for one plan: a gate's collision lags a tick behind it swinging
	 * open (King Lathas' camp). Only a walk the next plan still doesn't offer gives way, so the next tick plans again even
	 * when nothing the plan reads has changed: a player standing still would keep the held walk for good.
	 */
	private Route holdBlockedWalk(Route walk, Route best)
	{
		boolean hold = !walkHeld && walk.getFirstTransport() == null && !walk.isBanked() && isAlong(walk);
		walkHeld = hold;
		if (hold)
		{
			lastTrigger = null;
		}
		return hold ? walk : best;
	}

	/** The player is still on the walk's way, not teleported off it. */
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
		return at.distanceTo(end) <= leg.getFrom().distanceTo2D(end) + LANDED_TILES;
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
			&& first.getTo().distanceTo(from.getTile()) <= LANDED_TILES;
	}

	private static Route offered(Plan planned, String identity)
	{
		return routesOf(planned).stream().filter(route -> route != null && route.getIdentity().equals(identity)).findFirst().orElse(null);
	}

	private static List<Route> routesOf(Plan planned)
	{
		List<Route> routes = new ArrayList<>();
		routes.add(planned.getBest());
		routes.add(planned.getFollowed());
		return routes;
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
