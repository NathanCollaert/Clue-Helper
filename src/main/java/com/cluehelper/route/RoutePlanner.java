package com.cluehelper.route;

import com.cluehelper.ClueHelperConfig;
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
 * Keeps the current step's plan (PLAN §5.4 Re-planning and commitment). On the client thread each tick it
 * works out the start (a scene BFS when the player's tile moved) and re-plans only when the step, the
 * snapshot, the nearest node or the book state changes, never per tile. Plans run on the executor through
 * {@link PlannerRunner}; only the latest result lands.
 */
@Slf4j
@Singleton
public class RoutePlanner
{
	/** A new Best replaces the committed route only when it is at least this much faster (PLAN §5.4). */
	static final int COMMIT_MARGIN_TICKS = 5;
	/** Ticks a Spellbook Swap's free cast stays pending after 3617 turns 1 (B04). */
	static final int SWAP_WINDOW_TICKS = 200;
	private static final int NOT_REFUSED = -1;
	/** Walking in the scene re-plans when the walk to a target in it changes by this much: the commit margin. */
	static final int TARGET_WALK_BUCKET_TILES = 2 * COMMIT_MARGIN_TICKS;
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
	private final List<Consumer<Plan>> listeners = new CopyOnWriteArrayList<>();

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
	/** The scene walk to the nearest target, in buckets of {@link #TARGET_WALK_BUCKET_TILES}. */
	private int targetWalkBucket = NO_TARGET_IN_SCENE;
	private Trigger lastTrigger;
	@Getter
	private volatile Plan plan = Plan.NONE;
	/** The route StepGuide follows; kept unless a new one is 5 ticks faster or it became invalid. */
	@Getter
	private volatile Route committed;
	private String committedStep;
	/** 4816 when the game last refused a cape change: uses left count as 0 until it moves (PLAN §5.4 Consumption). */
	private int capeRefusedAt = NOT_REFUSED;
	/** Spellbook changes the game refused this session. */
	private final Set<String> refusedChanges = new HashSet<>();

	@Inject
	RoutePlanner(Client client, ClueLink clueLink, PlayerModel player, AvailabilityService availability, CatalogData catalogData,
		WalkData walkData, PlannerRunner runner, ClueHelperConfig config, BankMemory bankMemory, StepNeeds stepNeeds, PohService poh)
	{
		this.poh = poh;
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

	/** From the plugin's GameTick handler, after the player model's and availability's. */
	public void onGameTick()
	{
		Step step = clueLink.getCurrentStep();
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
		PlanInput input = PlanInput.builder()
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
		runner.submit(() -> Planner.plan(input), this::onPlanned);
	}

	/** Client thread: the game refused a cape change for today's limit, whatever 4816 says. */
	public void onCapeChangesRefused()
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		Integer used = snapshot == null ? null : snapshot.varbit(VarbitID.SB_SWAP_CHECK);
		capeRefusedAt = used == null ? 0 : used;
		lastTrigger = null;
		log.debug("cape change refused at 4816 = {}", capeRefusedAt);
	}

	/**
	 * Client thread: the game refused this teleport (the Music cape's "untrimmed cape" rule, B16): no var says so, so it
	 * isn't offered again this session.
	 */
	public void onTeleportRefused(String row)
	{
		onChangeRefused(row);
	}

	/** Client thread: the game refused this spellbook change; it isn't offered again this session. */
	public void onChangeRefused(String change)
	{
		if (refusedChanges.add(change))
		{
			lastTrigger = null;
			log.debug("spellbook change refused: {}", change);
		}
	}

	/** After a cape refusal, no cape uses are left until 4816 moves. */
	private PlayerSnapshot withRefusals(PlayerSnapshot snapshot)
	{
		if (capeRefusedAt == NOT_REFUSED)
		{
			return snapshot;
		}
		Integer used = snapshot.varbit(VarbitID.SB_SWAP_CHECK);
		if (used != null && used != capeRefusedAt)
		{
			capeRefusedAt = NOT_REFUSED;
			return snapshot;
		}
		Map<Integer, Integer> varbits = new HashMap<>(snapshot.getVarbits());
		varbits.put(VarbitID.SB_SWAP_CHECK, SpellbookState.CAPE_CHANGES_PER_DAY);
		SpellbookState book = snapshot.getSpellbook();
		SpellbookState noCapeUses = book == null ? null : new SpellbookState(book.getBook(), book.isPendingRevert(), 0,
			book.isArceuusUnlocked(), book.isAncientAccess(), book.isLunarAccess(), book.isSwapAccess());
		return snapshot.toBuilder().varbits(varbits).spellbook(noCapeUses).build();
	}

	/** The start of the next plan: the POH node, a scene BFS on the surface, else teleports only. */
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
			gatesVersion = snapshot.getVersion();
		}
		long started = System.nanoTime();
		SceneBfs.Scene scene = SceneBfs.Scene.capture(view);
		SceneBfs.Result bfs = scene == null ? null : SceneBfs.run(scene, position.getTile(), table, rowOpen);
		lastBfsNanos = System.nanoTime() - started;
		// A scene whose base has moved but whose collision flags are still loading walls the player in: the BFS then
		// reaches only their own tile. Keep the last start and try again next tick.
		if (sceneReloaded && bfs != null && bfs.reachedTiles() <= 1 && start != null)
		{
			lastTile = null;
			lastSceneBase = NO_SCENE;
			return;
		}
		nearestNode = bfs == null ? WalkTable.NO_NODE : nearest(table, bfs);
		int targetWalk = bfs == null ? SceneBfs.UNREACHED : step.getTargets().stream().mapToInt(bfs::cost).min().orElse(SceneBfs.UNREACHED);
		boolean targetInScene = targetWalk != SceneBfs.UNREACHED;
		targetWalkBucket = targetInScene ? targetWalk / TARGET_WALK_BUCKET_TILES : NO_TARGET_IN_SCENE;
		start = nearestNode != WalkTable.NO_NODE || targetInScene ? PlanStart.scene(position.getTile(), bfs)
			: PlanStart.teleportOnly(position.getTile());
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

	/** Client thread. Applies the commitment rule, then hands the plan on. */
	private void onPlanned(Plan planned)
	{
		plan = planned;
		committed = commit(planned);
		committedStep = planned.getStepKey();
		listeners.forEach(listener -> listener.accept(planned));
	}

	/**
	 * Keeps the committed route while it is still offered (same identity) and the new Best isn't 5 ticks faster
	 * than what it has left. A route no longer offered (item gone, cooldown hit, book changed) is invalid.
	 */
	private Route commit(Plan planned)
	{
		Route best = planned.getBest();
		Route current = committed;
		if (best == null || current == null || !Objects.equals(committedStep, planned.getStepKey()))
		{
			return best;
		}
		Route same = offered(planned, current.getIdentity());
		if (same == null || best.getTicks() <= same.getTicks() - COMMIT_MARGIN_TICKS)
		{
			return best;
		}
		return same;
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

	/** What a re-plan depends on; walking changes it only through the nearest node or the walk to a target in the scene. */
	@Value
	private static class Trigger
	{
		Step step;
		long snapshotVersion;
		/** A_carried is judged again on a new snapshot or when a cooldown clears. */
		long carriedVersion;
		long carriedValidUntil;
		int nearestNode;
		int targetWalkBucket;
		PlanStart.Kind startKind;
		SpellbookState spellbook;
		PlannerSettings settings;
		Set<String> refusedChanges;
		/** The remembered bank or the PIN state changed; while the bank is open, every withdraw. */
		long bankVersion;
		boolean atOpenBank;
		RequiredItems required;
		boolean foreignHouse;
	}
}
