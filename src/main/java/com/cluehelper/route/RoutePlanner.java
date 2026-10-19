package com.cluehelper.route;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.link.ClueLink;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.Position;
import com.cluehelper.player.PositionRules;
import com.cluehelper.player.RuneModel;
import com.cluehelper.player.SpellbookState;
import com.cluehelper.step.Step;
import com.cluehelper.transport.AvailabilityEngine;
import com.cluehelper.transport.AvailabilityService;
import com.cluehelper.transport.AvailabilitySet;
import com.cluehelper.transport.Catalog;
import com.cluehelper.transport.CatalogData;
import com.cluehelper.transport.Requirements;
import com.cluehelper.transport.VarCheck;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import net.runelite.api.coords.WorldPoint;

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

	private final Client client;
	private final ClueLink clueLink;
	private final PlayerModel player;
	private final AvailabilityService availability;
	private final CatalogData catalogData;
	private final WalkData walkData;
	private final PlannerRunner runner;
	private final ClueHelperConfig config;
	private final List<Consumer<Plan>> listeners = new CopyOnWriteArrayList<>();

	private volatile WalkData.Loaded walk;
	private volatile Catalog catalog;
	private WalkGates gates;
	private long gatesVersion = -1;
	private boolean[] rowOpen;
	private WorldPoint lastTile;
	@Getter
	private volatile PlanStart start;
	@Getter
	private volatile int nearestNode = WalkTable.NO_NODE;
	@Getter
	private volatile long lastBfsNanos;
	private Trigger lastTrigger;
	@Getter
	private volatile Plan plan = Plan.NONE;
	/** The route StepGuide follows; kept unless a new one is 5 ticks faster or it became invalid. */
	@Getter
	private volatile Route committed;
	private String committedStep;

	@Inject
	RoutePlanner(Client client, ClueLink clueLink, PlayerModel player, AvailabilityService availability, CatalogData catalogData,
		WalkData walkData, PlannerRunner runner, ClueHelperConfig config)
	{
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
		if (step == null || snapshot == null || runes == null || data == null || loadedCatalog == null)
		{
			if (step == null && plan != Plan.NONE)
			{
				stop();
				listeners.forEach(listener -> listener.accept(Plan.NONE));
			}
			return;
		}
		updateStart(data, snapshot, runes, step);
		PlannerSettings settings = PlannerSettings.from(config, snapshot.getAccountType());
		AvailabilitySet carried = availability.getCarried();
		Trigger trigger = new Trigger(step, snapshot.getVersion(), carried.getSnapshotVersion(), carried.getValidUntilMinute(),
			nearestNode, start.getKind(), snapshot.getSpellbook(), settings);
		if (trigger.equals(lastTrigger))
		{
			return;
		}
		lastTrigger = trigger;
		PlanInput input = PlanInput.builder()
			.step(step)
			.player(snapshot)
			.engine(new AvailabilityEngine(runes, id -> "item " + id))
			.nowMinutes(AvailabilityService.nowMinutes())
			.catalog(loadedCatalog)
			.table(data.getTable())
			.noTeleport(data.getNoTeleport())
			.start(start)
			.settings(settings)
			.revertTicksLeft(revertTicksLeft())
			.build();
		runner.submit(() -> Planner.plan(input), this::onPlanned);
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
		if (position.getTile().equals(lastTile) && start != null)
		{
			return;
		}
		lastTile = position.getTile();
		if (gatesVersion != snapshot.getVersion())
		{
			gates = new WalkGates(table, snapshot, new AvailabilityEngine(runes, id -> ""), AvailabilityService.nowMinutes());
			rowOpen = gates.rowOpen();
			gatesVersion = snapshot.getVersion();
		}
		long started = System.nanoTime();
		SceneBfs.Scene scene = SceneBfs.Scene.capture(client.getTopLevelWorldView());
		SceneBfs.Result bfs = scene == null ? null : SceneBfs.run(scene, position.getTile(), table, rowOpen);
		lastBfsNanos = System.nanoTime() - started;
		nearestNode = bfs == null ? WalkTable.NO_NODE : nearest(table, bfs);
		boolean targetInScene = bfs != null && step.getTargets().stream().anyMatch(t -> bfs.cost(t) != SceneBfs.UNREACHED);
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

	/** What a re-plan depends on; walking alone changes none of it unless the nearest node changes. */
	@Value
	private static class Trigger
	{
		Step step;
		long snapshotVersion;
		/** A_carried is judged again on a new snapshot or when a cooldown clears. */
		long carriedVersion;
		long carriedValidUntil;
		int nearestNode;
		PlanStart.Kind startKind;
		SpellbookState spellbook;
		PlannerSettings settings;
	}
}
