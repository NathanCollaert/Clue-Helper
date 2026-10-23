package com.cluehelper.hotcold;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.link.ClueLink;
import com.cluehelper.link.StepChange;
import com.cluehelper.memory.MemorySlot;
import com.cluehelper.memory.MemoryStore;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.Position;
import com.cluehelper.transport.AvailabilityService;
import com.cluehelper.route.OriginClass;
import com.cluehelper.route.PlanInput;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.PlannerRunner;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.route.SceneBfs;
import com.cluehelper.route.StepNeeds;
import com.cluehelper.route.WalkData;
import com.cluehelper.route.WalkTable;
import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepData;
import com.cluehelper.step.TargetKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.HotColdClue;
import net.runelite.client.plugins.cluescrolls.clues.hotcold.HotColdLocation;
import net.runelite.client.plugins.cluescrolls.clues.hotcold.HotColdSolver;
import net.runelite.client.util.Text;

/**
 * Client thread, except the plans, which run on their own {@link PlannerRunner} so they never supersede a route plan.
 * Reads core's hot/cold solver only at GameTick priority -1, and only ever copies its set.
 */
@Slf4j
@Singleton
public class HotColdService
{
	static final String KEY = "hotColdLogs";
	private static final String KEY_PREFIX = "hotcold:";
	private static final String BEGINNER = "beginner";
	private static final String MASTER = "master";
	private static final Map<String, String> DEVICE_NPC = Map.of(BEGINNER, "reldo", MASTER, "jorral");
	private static final int NO_CHECK = -1;
	/** Ranking-only ticks on a feel in the Wilderness while the Wilderness is avoided. */
	static final int WILDERNESS_PENALTY_TICKS = 20;
	/** Local probes: every second tile out to this, then every fourth out to {@link #LOCAL_FAR}. */
	private static final int LOCAL_NEAR = 8;
	private static final int LOCAL_FAR = 32;
	/** Above this the overworld ends; the device is unmeasured underground. */
	private static final int OVERWORLD_MAX_Y = 4500;
	/** Farther than running moves in a tick: a teleport. */
	private static final int JUMP_TILES = 4;
	private static final int NEAR_PROBE_TILES = 3;
	/** A feel this close to where "feel where you stand" was planned tells about the same. */
	private static final int HERE_DRIFT_TILES = 2;

	private final Client client;
	private final ClueLink clueLink;
	private final PlayerModel player;
	private final RoutePlanner routes;
	private final ProbeTarget probeTarget;
	private final StepData stepData;
	private final StepNeeds stepNeeds;
	private final AvailabilityService availability;
	private final ClueHelperConfig config;
	private final PlannerRunner runner;
	private final MemorySlot<FeelLogs> logs;
	private final List<Consumer<String>> traceListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<Planned>> planListeners = new CopyOnWriteArrayList<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	private Runnable unregisterStep;
	private List<Spot> beginnerSpots;
	private List<Spot> masterSpots;
	private List<WorldPoint> landingTiles;
	private boolean planPending;
	private WorldPoint lastTile;
	@Getter
	private volatile HotColdPlan plan;
	private volatile Set<String> coreSpots = Set.of();
	private volatile boolean coreReset;

	/** A plan with what it was planned from, for replays. */
	@lombok.Value
	public static class Planned
	{
		ProbeJob job;
		HotColdPlan plan;
	}

	@Inject
	HotColdService(Client client, ClueLink clueLink, PlayerModel player, RoutePlanner routes, ProbeTarget probeTarget,
		StepData stepData, StepNeeds stepNeeds, AvailabilityService availability, ClueHelperConfig config, MemoryStore memory,
		ScheduledExecutorService executor, ClientThread clientThread)
	{
		this.stepNeeds = stepNeeds;
		this.availability = availability;
		this.client = client;
		this.clueLink = clueLink;
		this.player = player;
		this.routes = routes;
		this.probeTarget = probeTarget;
		this.stepData = stepData;
		this.config = config;
		this.runner = new PlannerRunner(executor, clientThread);
		this.logs = memory.slot(KEY, FeelLogs.class, FeelLogs::new);
	}

	public void start()
	{
		probeTarget.setEnabled(config.hotColdPlanner());
		unregisterStep = clueLink.addListener(this::onStepChange);
		planPending = true;
	}

	public void stop()
	{
		if (unregisterStep != null)
		{
			unregisterStep.run();
		}
		runner.cancelAll();
		probeTarget.clear();
		plan = null;
	}

	/** Runs on the client thread after each plan. */
	public Runnable addListener(Runnable listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** Dev trace lines. */
	public Runnable addTraceListener(Consumer<String> listener)
	{
		traceListeners.add(listener);
		return () -> traceListeners.remove(listener);
	}

	public Runnable addPlanListener(Consumer<Planned> listener)
	{
		planListeners.add(listener);
		return () -> planListeners.remove(listener);
	}

	public void onConfigChanged()
	{
		probeTarget.setEnabled(config.hotColdPlanner());
		if (!config.hotColdPlanner())
		{
			runner.cancelAll();
			probeTarget.clear();
			plan = null;
		}
		planPending = true;
	}

	/** Call after a profile change: each profile has its own logs. */
	public void onProfileChanged()
	{
		probeTarget.clear();
		plan = null;
		planPending = true;
	}

	/** 3544 is set earlier in the same tick as the line, so any priority reads it. */
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		DeviceLine line = DeviceLine.parse(Text.removeTags(event.getMessage()));
		if (line == null)
		{
			return;
		}
		String key = logKey();
		if (key == null)
		{
			return;
		}
		if (line.getKind() == DeviceLine.Kind.FOUND)
		{
			logs.set(logs.get().solved(key));
			probeTarget.clear();
			plan = null;
			trace("found: log of " + key + " cleared, last reading kept as the anchor");
			return;
		}
		if (line.getKind() != DeviceLine.Kind.READING)
		{
			return;
		}
		Reading reading = reading(line);
		FeelLogs updated = logs.get().with(key, reading);
		logs.set(updated);
		planPending = true;
		trace("reading " + key + " " + reading.getX() + "," + reading.getY() + "," + reading.getPlane() + " " + line.getBand().getText()
			+ (line.getComparison() == null ? "" : ", " + line.getComparison().getText()) + " (log " + updated.get(key).size() + ")");
	}

	/** Call from a GameTick handler at priority -1, after the route planner's. */
	public void onGameTick()
	{
		Step step = clueLink.getCurrentStep();
		if (!isHotCold(step) || !config.hotColdPlanner())
		{
			return;
		}
		readCore();
		WorldPoint tile = player.getPosition().getTile();
		boolean jumped = tile != null && lastTile != null && (tile.getPlane() != lastTile.getPlane() || tile.distanceTo2D(lastTile) > JUMP_TILES);
		lastTile = tile;
		HotColdPlan current = plan;
		boolean stale = current == null || !current.getStepKey().equals(step.getKey());
		if (jumped && current != null && current.getTile() != null
			&& (tile.getPlane() != current.getTile().getPlane() || tile.distanceTo2D(current.getTile()) > NEAR_PROBE_TILES))
		{
			planPending = true;
		}
		if (walkedOffHere(current, tile) && !runner.isWorking())
		{
			planPending = true;
		}
		if ((planPending || stale && !runner.isWorking()) && ownsDevice(step))
		{
			submit(step);
		}
	}

	private static boolean walkedOffHere(HotColdPlan current, WorldPoint tile)
	{
		boolean here = current != null && current.getChoice() != null && current.getChoice().getProbe().getKind() == Probe.Kind.HERE;
		return here && tile != null && (tile.getPlane() != current.getTile().getPlane() || tile.distanceTo2D(current.getTile()) > HERE_DRIFT_TILES);
	}

	private void onStepChange(StepChange change, Step step)
	{
		if (change == StepChange.NEW && isHotCold(step))
		{
			planPending = true;
			if (config.hotColdPlanner() && ownsDevice(step))
			{
				probeTarget.choosing(step.getKey());
			}
		}
		else if (change == StepChange.CLEARED || change == StepChange.NEW)
		{
			forgetIfDug(plan);
			probeTarget.clear();
			plan = null;
		}
		if (change == StepChange.UPDATED && isHotCold(step))
		{
			listeners.forEach(Runnable::run);
		}
	}

	/**
	 * A beginner dig gives the next clue without the "cools" line, so a step that ends on its dig tile is taken as
	 * solved; otherwise the next clue of the tier would start from this one's readings. Banking the clue there is the
	 * case this misjudges.
	 */
	private void forgetIfDug(HotColdPlan last)
	{
		WorldPoint tile = player.getPosition().getTile();
		boolean dig = last != null && (last.getAction() == HotColdPlan.Action.DIG_HERE || last.getAction() == HotColdPlan.Action.DIG_SPOT);
		if (dig && tile != null && tile.getPlane() == last.getTile().getPlane()
			&& tile.distanceTo2D(last.getTile()) <= Band.digRadius(last.getStepKey().startsWith(KEY_PREFIX + BEGINNER)))
		{
			logs.set(logs.get().solved(last.getStepKey()));
			trace("dug at " + tile + ": log of " + last.getStepKey() + " cleared, last reading kept as the anchor");
		}
	}

	private void readCore()
	{
		ClueScroll clue = clueLink.coreClue();
		HotColdSolver solver = clue instanceof HotColdClue ? ((HotColdClue) clue).getHotColdSolver() : null;
		if (solver == null)
		{
			coreSpots = Set.of();
			coreReset = true;
			return;
		}
		Set<String> names = new HashSet<>();
		for (HotColdLocation location : new HashSet<>(solver.getPossibleLocations()))
		{
			names.add(location.name());
		}
		coreSpots = Set.copyOf(names);
		coreReset = solver.getLastWorldPoint() == null;
	}

	private void submit(Step step)
	{
		WalkData.Loaded walk = routes.getWalk();
		Step landings = walk == null ? null : landingStep(step, walk.getTable());
		PlanInput landingSearch = landings == null ? null : routes.inputFor(landings, stepNeeds.of(landings));
		if (landingSearch == null)
		{
			return;
		}
		planPending = false;
		boolean beginner = BEGINNER.equals(step.getTier());
		boolean carried = carries(beginner);
		ProbeJob job = ProbeJob.builder()
			.stepKey(step.getKey())
			.beginner(beginner)
			.spots(spots(beginner))
			.readings(logs.get().get(step.getKey()))
			.here(carried && player.getPosition().getKind() == Position.Kind.WORLD ? player.getPosition().getTile() : null)
			.local(carried ? localProbes(walk) : List.of())
			.landingSearch(landingSearch)
			.costs(ProbePlanner.Costs.builder()
				.wildernessPenaltyTicks(landingSearch.getSettings().isAvoidWilderness() ? WILDERNESS_PENALTY_TICKS : 0).build())
			.avoidWilderness(landingSearch.getSettings().isAvoidWilderness())
			.build();
		trace("plan " + step.getKey() + " from " + job.getReadings().size() + " readings");
		runner.submit(job::run, result -> onPlanned(job, result));
	}

	private void onPlanned(ProbeJob job, HotColdPlan planned)
	{
		Step step = clueLink.getCurrentStep();
		if (step == null || !planned.getStepKey().equals(step.getKey()))
		{
			return;
		}
		plan = planned;
		probeTarget.set(target(planned));
		trace(describe(planned) + coreComparison(planned));
		planListeners.forEach(listener -> listener.accept(new Planned(job, planned)));
		listeners.forEach(Runnable::run);
	}

	private static ProbeTarget.Target target(HotColdPlan planned)
	{
		switch (planned.getAction())
		{
			case FEEL:
				boolean here = planned.getChoice().getProbe().getKind() == Probe.Kind.HERE;
				return new ProbeTarget.Target(planned.getStepKey(), planned.getTile(), here ? "Feel the device where you stand"
					: "Feel the device here", false, here);
			case DIG_HERE:
			case DIG_SPOT:
				return new ProbeTarget.Target(planned.getStepKey(), planned.getTile(), "Dig here", true, false);
			default:
				return null;
		}
	}

	/** The PROBES search's targets: every overworld walk node, each a landing or a walk away from one. */
	private Step landingStep(Step step, WalkTable table)
	{
		if (landingTiles == null)
		{
			List<WorldPoint> tiles = new ArrayList<>();
			for (int node = 0; node < table.nodeCount(); node++)
			{
				WorldPoint tile = table.tile(node);
				if (tile != null && tile.getPlane() == 0 && tile.getY() < OVERWORLD_MAX_Y)
				{
					tiles.add(tile);
				}
			}
			landingTiles = List.copyOf(tiles);
		}
		return step.toBuilder().kind(TargetKind.PROBES).targets(landingTiles)
			.requirements(step.getRequirements().plus(Device.requirement(BEGINNER.equals(step.getTier())))).build();
	}

	private List<Probe> localProbes(WalkData.Loaded walk)
	{
		PlanStart start = routes.getStart();
		WorldPoint here = player.getPosition().getTile();
		if (start == null || start.getKind() != PlanStart.Kind.SCENE || start.getScene() == null || here == null)
		{
			return List.of();
		}
		SceneBfs.Result scene = start.getScene();
		List<Probe> probes = new ArrayList<>();
		for (int dx = -LOCAL_FAR; dx <= LOCAL_FAR; dx++)
		{
			for (int dy = -LOCAL_FAR; dy <= LOCAL_FAR; dy++)
			{
				int reach = Math.max(Math.abs(dx), Math.abs(dy));
				int stride = reach <= LOCAL_NEAR ? 2 : 4;
				if (reach == 0 || dx % stride != 0 || dy % stride != 0)
				{
					continue;
				}
				WorldPoint tile = new WorldPoint(here.getX() + dx, here.getY() + dy, here.getPlane());
				int cost = scene.cost(tile);
				if (cost != SceneBfs.UNREACHED)
				{
					int ticks = (cost + 1) / 2;
					probes.add(new Probe(tile, Probe.Kind.LOCAL, ticks, ticks, OriginClass.of(tile, walk.getNoTeleport()).isWilderness()));
				}
			}
		}
		return probes;
	}

	private List<Spot> spots(boolean beginner)
	{
		if (beginnerSpots == null)
		{
			beginnerSpots = Spots.of(stepData.index(), true);
			masterSpots = Spots.of(stepData.index(), false);
		}
		return beginner ? beginnerSpots : masterSpots;
	}

	/**
	 * The current hot/cold step's, or, while core has no clue (banked, not read), the newest log of the carried device's
	 * tier: the device works without the clue.
	 */
	private String logKey()
	{
		Step step = clueLink.getCurrentStep();
		if (isHotCold(step))
		{
			return step.getKey();
		}
		boolean beginner = carries(true);
		boolean master = carries(false);
		if (beginner == master)
		{
			return master ? logs.get().latestKey(KEY_PREFIX) : null;
		}
		String tier = beginner ? BEGINNER : MASTER;
		String latest = logs.get().latestKey(KEY_PREFIX + tier + ":");
		return latest != null ? latest : KEY_PREFIX + tier + ":" + DEVICE_NPC.get(tier);
	}

	private Reading reading(DeviceLine line)
	{
		int coord = client.getVarpValue(VarPlayerID.TRAIL_HOTCOLD_LAST_CHECK_COORD);
		if (coord == NO_CHECK)
		{
			WorldPoint tile = player.getPosition().getTile();
			return new Reading(tile.getX(), tile.getY(), tile.getPlane(), line.getBand(), line.getComparison());
		}
		return new Reading(coord >> 14 & 0x3FFF, coord & 0x3FFF, coord >> 28 & 3, line.getBand(), line.getComparison());
	}

	/** Carried, or in the remembered bank: the probe routes then start with a bank stop for it. */
	private boolean ownsDevice(Step step)
	{
		boolean beginner = BEGINNER.equals(step.getTier());
		if (carries(beginner))
		{
			return true;
		}
		PlayerSnapshot banked = availability.getBankedSnapshot();
		return banked != null && banked.getBank().containsKey(Device.itemId(beginner));
	}

	private boolean carries(boolean beginner)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		return snapshot != null && snapshot.getInventory().containsKey(Device.itemId(beginner));
	}

	private static boolean isHotCold(Step step)
	{
		return step != null && ClueTypes.HOT_COLD.equals(step.getType());
	}

	/** Dev only: starts the current hot/cold step's log again, its last reading kept as the anchor. */
	public String restartLog()
	{
		Step step = clueLink.getCurrentStep();
		if (!isHotCold(step))
		{
			return "No hot/cold step";
		}
		logs.set(logs.get().solved(step.getKey()));
		planPending = true;
		return "Feel log of " + step.getKey() + " restarted";
	}

	/** Dev only. */
	public List<String> describeLog()
	{
		Step step = clueLink.getCurrentStep();
		if (!isHotCold(step))
		{
			return List.of("No hot/cold step");
		}
		List<String> lines = new ArrayList<>();
		List<Reading> readings = logs.get().get(step.getKey());
		lines.add(step.getKey() + ": " + readings.size() + " readings");
		readings.forEach(reading -> lines.add("  " + reading));
		HotColdPlan current = plan;
		lines.add(current == null ? "no plan" : describe(current));
		return lines;
	}

	public Set<String> getCoreSpots()
	{
		return coreSpots;
	}

	public boolean isCoreReset()
	{
		return coreReset;
	}

	public int hitpoints()
	{
		return client.getBoostedSkillLevel(Skill.HITPOINTS);
	}

	public List<Spot> spotsOf(boolean beginner)
	{
		return spots(beginner);
	}

	/** For the verdict "did the grid keep a spot core dropped". */
	private String coreComparison(HotColdPlan planned)
	{
		Set<String> core = coreSpots;
		List<String> dropped = new ArrayList<>();
		for (String spot : planned.getKept().keySet())
		{
			if (!core.contains(spot))
			{
				dropped.add(spot);
			}
		}
		return "; core " + core.size() + (coreReset ? " (reset)" : "") + (dropped.isEmpty() ? "" : ", core dropped " + dropped);
	}

	private static String describe(HotColdPlan planned)
	{
		StringBuilder line = new StringBuilder(planned.getAction().name()).append(' ').append(planned.getTile())
			.append(" kept ").append(planned.getKept().size()).append(planned.isExact() ? "" : " (closest fits)")
			.append(" from ").append(planned.getReadings()).append(" readings");
		if (planned.getChoice() != null)
		{
			ProbePlanner.Choice choice = planned.getChoice();
			line.append(" probe ").append(choice.getProbe().getKind()).append(' ').append(choice.getProbe().getTicks()).append('t')
				.append(String.format(" %.2f bits cost %d", choice.getBits(), choice.getCostTicks()))
				.append(" branches ").append(choice.getBranches().size());
		}
		return line.append(" probes ").append(planned.getProbes()).append(" landings ").append(planned.getLandings())
			.append(" in ").append(planned.getNanos() / 1_000_000).append(" ms (landings ").append(planned.getLandingNanos() / 1_000_000)
			.append(" ms)").toString();
	}

	private void trace(String line)
	{
		log.debug("hot/cold {}", line);
		traceListeners.forEach(listener -> listener.accept(line));
	}
}
