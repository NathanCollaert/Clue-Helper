package com.cluehelper.hotcold;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.link.*;
import com.cluehelper.memory.*;
import com.cluehelper.player.*;
import com.cluehelper.step.*;
import com.cluehelper.transport.AvailabilityService;
import com.cluehelper.route.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.*;
import javax.inject.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.cluescrolls.clues.*;
import net.runelite.client.plugins.cluescrolls.clues.hotcold.*;
import net.runelite.client.util.Text;

/**
 * Client thread, except the plans, which run on their own {@link PlannerRunner} so they never supersede a route plan.
 * Reads core's hot/cold solver only at GameTick priority -1, and only ever copies its set.
 */
@Slf4j
@Singleton
public class HotColdService
{
	public static final String KEY = "hotColdLogs";
	private static final int NO_CHECK = -1;
	/** Ranking-only ticks on a feel in the Wilderness while the Wilderness is avoided. */
	static final int WILDERNESS_PENALTY_TICKS = 20;
	/** Local probes: every second tile out to this, then every fourth out to {@link #LOCAL_FAR}. */
	private static final int LOCAL_NEAR = 8;
	private static final int LOCAL_FAR = 32;
	/** Above this the overworld ends; the device is unmeasured underground. */
	private static final int OVERWORLD_MAX_Y = 4500;
	private static final int NEAR_PROBE_TILES = 3;
	/** A feel this close to where "feel where you stand" was planned tells about the same. */
	private static final int HERE_DRIFT_TILES = 2;

	@Inject
	private Client client;
	@Inject
	private ClueLink clueLink;
	@Inject
	private PlayerModel player;
	@Inject
	private RoutePlanner routes;
	@Inject
	private ProbeTarget probeTarget;
	@Inject
	private StepData stepData;
	@Inject
	private StepNeeds stepNeeds;
	@Inject
	private AvailabilityService availability;
	@Inject
	private ClueHelperConfig config;
	private PlannerRunner runner;
	private MemorySlot<FeelLogs> logs;
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
	void init(MemoryStore memory, ScheduledExecutorService executor, ClientThread clientThread)
	{
		runner = new PlannerRunner(executor, clientThread);
		logs = memory.slot(KEY, FeelLogs.class, FeelLogs::new);
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
		clearPlan();
	}

	/** Runs on the client thread after each plan. */
	public Runnable addListener(Runnable listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
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
			clearPlan();
		}
		planPending = true;
	}

	/** Call after a profile change: each profile has its own logs. */
	public void onProfileChanged()
	{
		clearPlan();
		planPending = true;
	}

	/** {@code TRAIL_HOTCOLD_LAST_CHECK_COORD} is set earlier in the same tick as the line, so any priority reads it. */
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
			clearPlan();
			log.debug("hot/cold found: log of {} cleared, last reading kept as the anchor", key);
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
		log.debug("hot/cold reading {} {} (log {})", key, reading, updated.get(key).size());
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
		boolean jumped = PositionRules.teleported(lastTile, tile);
		lastTile = tile;
		HotColdPlan current = plan;
		boolean stale = current == null || !current.getStepKey().equals(step.getKey());
		if (jumped && current != null && current.getTile() != null
			&& !PositionRules.near(tile, current.getTile(), NEAR_PROBE_TILES))
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

	private void clearPlan()
	{
		probeTarget.clear();
		plan = null;
	}

	private static boolean walkedOffHere(HotColdPlan current, WorldPoint tile)
	{
		boolean here = current != null && current.getChoice() != null && current.getChoice().getProbe().getKind() == Probe.Kind.HERE;
		return here && tile != null && !PositionRules.near(tile, current.getTile(), HERE_DRIFT_TILES);
	}

	private void onStepChange(StepChange change, Step step)
	{
		if (change == StepChange.CLEARED || change == StepChange.NEW)
		{
			forgetIfDug(plan);
			clearPlan();
		}
		if (change == StepChange.NEW && isHotCold(step))
		{
			planPending = true;
			if (config.hotColdPlanner() && ownsDevice(step))
			{
				probeTarget.choosing(step.getKey());
			}
		}
		if (change == StepChange.UPDATED && isHotCold(step))
		{
			listeners.forEach(Runnable::run);
		}
	}

	/**
	 * A beginner dig gives the next clue without the "cools" line, so a step that ends on any spot of its tier is taken
	 * as solved, whether or not the plan pointed there; otherwise the next clue of the tier would start from this one's
	 * readings. Banking the clue there is the case this misjudges.
	 */
	private void forgetIfDug(HotColdPlan last)
	{
		WorldPoint tile = player.getPosition().getTile();
		if (last != null && tile != null && standsOnSpot(tile, last.getTier()))
		{
			logs.set(logs.get().solved(last.getStepKey()));
			log.debug("hot/cold dug at {}: log of {} cleared, last reading kept as the anchor", tile, last.getStepKey());
		}
	}

	private boolean standsOnSpot(WorldPoint tile, HotColdTier tier)
	{
		return tile.getPlane() == 0 && spotsOf(tier).stream().anyMatch(spot ->
			Hypotheses.distance(tile, spot.getX(), spot.getY()) <= Band.digRadius(tier) + spot.getRadius());
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
		coreSpots = new HashSet<>(solver.getPossibleLocations()).stream().map(HotColdLocation::name).collect(Collectors.toUnmodifiableSet());
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
		HotColdTier tier = HotColdTier.of(step.getTier());
		boolean carried = carries(tier);
		ProbeJob job = ProbeJob.builder()
			.stepKey(step.getKey())
			.tier(tier)
			.spots(spotsOf(tier))
			.readings(logs.get().get(step.getKey()))
			.core(new CoreSpots(coreReset ? Set.of() : coreSpots, !coreReset))
			.here(carried && player.getPosition().getKind() == Position.Kind.WORLD ? player.getPosition().getTile() : null)
			.local(carried ? localProbes(walk) : List.of())
			.landingSearch(landingSearch)
			.costs(ProbePlanner.Costs.builder()
				.wildernessPenaltyTicks(landingSearch.getSettings().isAvoidWilderness() ? WILDERNESS_PENALTY_TICKS : 0).build())
			.avoidWilderness(landingSearch.getSettings().isAvoidWilderness())
			.build();
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
		routes.onTravelStepChanged();
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
			case NONE:
				return null;
			default:
				throw new IllegalArgumentException("unhandled action " + planned.getAction());
		}
	}

	/** The PROBES search's targets: every overworld walk node, each a landing or a walk away from one. */
	private Step landingStep(Step step, WalkTable table)
	{
		if (landingTiles == null)
		{
			landingTiles = IntStream.range(0, table.nodeCount()).mapToObj(table::tile)
				.filter(tile -> tile != null && tile.getPlane() == 0 && tile.getY() < OVERWORLD_MAX_Y).collect(Collectors.toUnmodifiableList());
		}
		return step.toBuilder().kind(TargetKind.PROBES).targets(landingTiles)
			.requirements(step.getRequirements().plus(HotColdTier.of(step.getTier()).getDevice())).build();
	}

	private List<Probe> localProbes(WalkData.Loaded walk)
	{
		PlanStart start = routes.getStart();
		WorldPoint here = player.getPosition().getTile();
		if (start == null || start.getKind() != PlanStart.Kind.SCENE || start.getWalk() == null || here == null)
		{
			return List.of();
		}
		WalkCosts fromHere = start.getWalk();
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
				int cost = fromHere.cost(tile);
				if (cost != WalkCosts.UNREACHED)
				{
					int ticks = (Running.halfTicks(cost) + 1) / 2;
					probes.add(new Probe(tile, Probe.Kind.LOCAL, ticks, ticks, OriginClass.of(tile, walk.getNoTeleport()).isWilderness()));
				}
			}
		}
		return probes;
	}

	public List<Spot> spotsOf(HotColdTier tier)
	{
		if (beginnerSpots == null)
		{
			beginnerSpots = Spots.of(stepData.index(), HotColdTier.BEGINNER);
			masterSpots = Spots.of(stepData.index(), HotColdTier.MASTER);
		}
		return tier == HotColdTier.BEGINNER ? beginnerSpots : masterSpots;
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
		boolean beginner = carries(HotColdTier.BEGINNER);
		boolean master = carries(HotColdTier.MASTER);
		if (beginner == master)
		{
			return master ? logs.get().latestKey(StepKey.HOT_COLD_PREFIX) : null;
		}
		HotColdTier tier = beginner ? HotColdTier.BEGINNER : HotColdTier.MASTER;
		String latest = logs.get().latestKey(StepKey.hotColdPrefix(tier));
		return latest != null ? latest : StepKey.hotCold(tier, tier.getDeviceNpc());
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
		HotColdTier tier = HotColdTier.of(step.getTier());
		if (carries(tier))
		{
			return true;
		}
		PlayerSnapshot banked = availability.getBankedSnapshot();
		return banked != null && banked.getBank().containsKey(tier.getDeviceItemId());
	}

	private boolean carries(HotColdTier tier)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		return snapshot != null && snapshot.getInventory().containsKey(tier.getDeviceItemId());
	}

	private static boolean isHotCold(Step step)
	{
		return step != null && ClueTypes.HOT_COLD.equals(step.getType());
	}

	public int hitpoints()
	{
		return client.getBoostedSkillLevel(Skill.HITPOINTS);
	}

}
