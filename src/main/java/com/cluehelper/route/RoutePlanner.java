package com.cluehelper.route;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.hotcold.ProbeTarget;
import com.cluehelper.link.ClueLink;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.Position;
import com.cluehelper.player.PositionRules;
import com.cluehelper.player.RuneModel;
import com.cluehelper.player.SpellbookState;
import com.cluehelper.poh.HouseOwnership;
import com.cluehelper.poh.PohService;
import com.cluehelper.step.StageTarget;
import com.cluehelper.step.Step;
import com.cluehelper.transport.AvailabilityEngine;
import com.cluehelper.transport.AvailabilityService;
import com.cluehelper.transport.AvailabilitySet;
import com.cluehelper.transport.Catalog;
import com.cluehelper.transport.CatalogData;
import com.cluehelper.transport.Requirements;
import com.cluehelper.transport.VarCheck;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;

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
	static final int SWAP_WINDOW_TICKS = 200;
	private static final int NOT_REFUSED = -1;
	static final int TARGET_WALK_BUCKET_TILES = 2 * COMMIT_MARGIN_TICKS;
	/** The guide's landing radius: some teleports land anywhere in a small area. */
	private static final int LANDED_TILES = 10;
	private static final int POCKET_TILES = 3;
	private static final int NO_TARGET_IN_SCENE = -1;
	private static final long NO_SCENE = Long.MIN_VALUE;

	private final Client client;
	private final ClueLink clueLink;
	private final PlayerModel player;
	private final AvailabilityService availability;
	private final CatalogData catalogData;
	private final WalkData walkData;
	private final PlannerRunner runner;
	private final ClueHelperConfig config;
	private final BankMemory bankMemory;
	private final StepNeeds stepNeeds;
	private final PohService poh;
	private final ProbeTarget probeTarget;
	private final StageTarget stageTarget;
	private final SceneDoors doors;
	private final List<Consumer<Plan>> listeners = new CopyOnWriteArrayList<>();
	private final List<BiConsumer<Plan, PlanInput>> inputListeners = new CopyOnWriteArrayList<>();

	private volatile WalkData.Loaded walk;
	private volatile Catalog catalog;
	private WalkGates gates;
	private long gatesVersion = -1;
	private boolean[] rowOpen;
	private WorldPoint lastTile;
	/** After a teleport the scene loads a tick after the tile moves: a BFS over the old scene must be redone. */
	private long lastSceneBase = NO_SCENE;
	@Getter
	private volatile PlanStart start;
	@Getter
	private volatile int nearestNode = WalkTable.NO_NODE;
	@Getter
	private volatile long lastBfsNanos;
	private int targetWalkBucket = NO_TARGET_IN_SCENE;
	private Trigger lastTrigger;
	@Getter
	private volatile Plan plan = Plan.NONE;
	@Getter
	private volatile Route committed;
	private String committedStep;
	private int swapCheckAtCapeRefusal = NOT_REFUSED;
	private final Set<String> refusedChanges = new HashSet<>();

	@Inject
	RoutePlanner(Client client, ClueLink clueLink, PlayerModel player, AvailabilityService availability, CatalogData catalogData,
		WalkData walkData, PlannerRunner runner, ClueHelperConfig config, BankMemory bankMemory, StepNeeds stepNeeds, PohService poh,
		ProbeTarget probeTarget, StageTarget stageTarget, SceneDoors doors)
	{
		this.stageTarget = stageTarget;
		this.doors = doors;
		this.poh = poh;
		this.probeTarget = probeTarget;
		this.bankMemory = bankMemory;
		this.stepNeeds = stepNeeds;
		this.client = client;
		this.clueLink = clueLink;
		this.player = player;
		this.availability = availability;
		this.catalogData = catalogData;
		this.walkData = walkData;
		this.runner = runner;
		this.config = config;
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
		plan = Plan.NONE;
		committed = null;
		committedStep = null;
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

	/** Call after the player model's and availability's GameTick handlers. */
	public void onGameTick()
	{
		Step step = stageTarget.travelStep(probeTarget.travelStep(clueLink.getCurrentStep()));
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
		snapshot = withRefusals(snapshot);
		updateStart(data, snapshot, runes, step);
		PlannerSettings settings = PlannerSettings.from(config, snapshot.getAccountType());
		AvailabilitySet carried = availability.getCarried();
		PlayerSnapshot banked = availability.getBankedSnapshot();
		RequiredItems required = stepNeeds.of(step);
		boolean atOpenBank = bankMemory.isBankOpen();
		boolean foreignHouse = poh.isInHouse() && poh.getOwnership() == HouseOwnership.Ownership.FOREIGN;
		Trigger trigger = new Trigger(step, snapshot.getVersion(), carried.getSnapshotVersion(), carried.getValidUntilMinute(),
			nearestNode, targetWalkBucket, start.getKind(), snapshot.getSpellbook(), settings, Set.copyOf(refusedChanges),
			bankMemory.getVersion(), atOpenBank, required, foreignHouse);
		if (trigger.equals(lastTrigger))
		{
			return;
		}
		lastTrigger = trigger;
		PlanInput input = input(step, snapshot, runes, data, loadedCatalog, settings, banked, required, atOpenBank, foreignHouse);
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
		snapshot = withRefusals(snapshot);
		boolean foreignHouse = poh.isInHouse() && poh.getOwnership() == HouseOwnership.Ownership.FOREIGN;
		return input(step, snapshot, runes, data, loadedCatalog, PlannerSettings.from(config, snapshot.getAccountType()),
			availability.getBankedSnapshot(), required, bankMemory.isBankOpen(), foreignHouse);
	}

	/** Loaded walk data; null until it is. */
	public WalkData.Loaded getWalk()
	{
		return walk;
	}

	private PlanInput input(Step step, PlayerSnapshot snapshot, RuneModel runes, WalkData.Loaded data, Catalog loadedCatalog,
		PlannerSettings settings, PlayerSnapshot banked, RequiredItems required, boolean atOpenBank, boolean foreignHouse)
	{
		return PlanInput.builder()
			.step(step)
			.player(snapshot)
			.banked(banked == null ? null : withRefusals(banked))
			.pinPending(bankMemory.isPinPending())
			.atOpenBank(atOpenBank)
			.required(required)
			.engine(new AvailabilityEngine(runes, id -> "item " + id))
			.nowMinutes(AvailabilityService.nowMinutes())
			.catalog(loadedCatalog)
			.table(data.getTable())
			.noTeleport(data.getNoTeleport())
			.start(start)
			.settings(settings)
			.revertTicksLeft(revertTicksLeft())
			.refusedChanges(Set.copyOf(refusedChanges))
			.foreignHouseStart(foreignHouse)
			.build();
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
	public void onTeleportRefused(String row)
	{
		onChangeRefused(row);
	}

	/** Client thread. */
	public void onChangeRefused(String change)
	{
		if (refusedChanges.add(change))
		{
			lastTrigger = null;
			log.debug("spellbook change refused: {}", change);
		}
	}

	/** After a cape refusal, no cape uses are left until SB_SWAP_CHECK moves. */
	private PlayerSnapshot withRefusals(PlayerSnapshot snapshot)
	{
		if (swapCheckAtCapeRefusal == NOT_REFUSED)
		{
			return snapshot;
		}
		Integer used = snapshot.varbit(VarbitID.SB_SWAP_CHECK);
		if (used != null && used != swapCheckAtCapeRefusal)
		{
			swapCheckAtCapeRefusal = NOT_REFUSED;
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
		long sceneBase = view == null ? NO_SCENE : (long) view.getBaseX() << 32 | view.getBaseY() & 0xFFFFFFFFL;
		boolean sceneReloaded = sceneBase != lastSceneBase;
		if (position.getTile().equals(lastTile) && start != null && !sceneReloaded)
		{
			return;
		}
		lastTile = position.getTile();
		lastSceneBase = sceneBase;
		if (gatesVersion != snapshot.getVersion())
		{
			gates = new WalkGates(table, snapshot, new AvailabilityEngine(runes, id -> ""), AvailabilityService.nowMinutes());
			rowOpen = gates.rowOpen();
			doors.playerChanged(snapshot);
			gatesVersion = snapshot.getVersion();
		}
		long started = System.nanoTime();
		SceneBfs.Scene scene = doors.open(client, view, SceneBfs.Scene.capture(view), table);
		SceneBfs.Result bfs = scene == null ? null : SceneBfs.run(scene, position.getTile(), table, rowOpen);
		lastBfsNanos = System.nanoTime() - started;
		// Collision flags still loading after a reload, or the player mid-obstacle on a blocked tile (stepping stones,
		// a log): the BFS reaches only the player's tile, so the last start stands until the next tick.
		boolean onlyOwnTile = bfs != null && bfs.reachedTiles() <= 1;
		if (onlyOwnTile && start != null)
		{
			lastTile = null;
			lastSceneBase = NO_SCENE;
			return;
		}
		int nearestHere = bfs == null ? WalkTable.NO_NODE : nearest(table, bfs);
		int targetWalk = bfs == null ? SceneBfs.UNREACHED : step.getTargets().stream().mapToInt(bfs::cost).min().orElse(SceneBfs.UNREACHED);
		boolean targetInScene = targetWalk != SceneBfs.UNREACHED;
		// Mid-obstacle in a pocket with no node (south of Kaleb's, anim 741), a few tiles from a start that had nodes
		boolean pocket = nearestHere == WalkTable.NO_NODE && !targetInScene && start != null && start.getKind() == PlanStart.Kind.SCENE
			&& start.getTile() != null && start.getTile().distanceTo2D(position.getTile()) <= POCKET_TILES;
		if (pocket)
		{
			lastTile = null;
			lastSceneBase = NO_SCENE;
			return;
		}
		nearestNode = nearestHere;
		targetWalkBucket = targetInScene ? targetWalk / TARGET_WALK_BUCKET_TILES : NO_TARGET_IN_SCENE;
		start = nearestNode != WalkTable.NO_NODE || targetInScene ? PlanStart.scene(position.getTile(), bfs)
			: PlanStart.teleportOnly(position.getTile(), bfs);
	}

	private static int nearest(WalkTable table, SceneBfs.Result bfs)
	{
		int best = WalkTable.NO_NODE;
		int bestCost = SceneBfs.UNREACHED;
		for (int node = 0; node < table.nodeCount(); node++)
		{
			int cost = bfs.cost(table.placed(node));
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
		committed = commit(planned);
		committedStep = planned.getStepKey();
		listeners.forEach(listener -> listener.accept(planned));
		inputListeners.forEach(listener -> listener.accept(planned, input));
	}

	private Route commit(Plan planned)
	{
		Route best = planned.getBest();
		Route current = committed;
		if (best == null || current == null || !Objects.equals(committedStep, planned.getStepKey()))
		{
			return best;
		}
		Route same = offered(planned, current.getIdentity());
		if (same == null || best.getTicks() <= same.getTicks() - COMMIT_MARGIN_TICKS || landsWhereItStarts(same))
		{
			return best;
		}
		return same;
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
