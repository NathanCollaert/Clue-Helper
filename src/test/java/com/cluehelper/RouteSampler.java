package com.cluehelper;

import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.route.DriftSteps;
import com.cluehelper.route.Plan;
import com.cluehelper.route.PlanInput;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.Planner;
import com.cluehelper.route.PlannerSettings;
import com.cluehelper.route.Route;
import com.cluehelper.route.SceneBfs;
import com.cluehelper.route.WalkData;
import com.cluehelper.route.WalkGates;
import com.cluehelper.step.Step;
import com.cluehelper.transport.AvailabilityEngine;
import com.cluehelper.transport.AvailabilityService;
import com.cluehelper.transport.Catalog;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

/**
 * B10's in-client check without ten clue scrolls: plans a fixed spread of real steps from the player's live
 * snapshot and position ({@code [CH-SAMPLE]} lines, read against the world map), and times the scene BFS warm.
 */
@Slf4j
final class RouteSampler
{
	static final String TAG = "[CH-SAMPLE]";
	private static final List<String> STEPS = List.of(
		"cryptic:search the crates in a bank in varrock.",
		"cryptic:you will need to under-cook to solve this one.",
		"map:w348",
		"emote:12021",
		"coordinate:2840,3423,0",
		"anagram:12566",
		"cipher:19762",
		"coordinate:2303,3328,0",
		"cryptic:anger those who adhere to saradomin's edicts to prevent travel.",
		"skill:CHARLIE:i need to give charlie a cooked pike.");
	private static final int BFS_RUNS = 20;

	private final Client client;
	private final ClueHelperPlugin clueHelper;
	private final Catalog catalog;
	private final WalkData.Loaded walk;
	private final Executor executor;

	RouteSampler(Client client, ClueHelperPlugin clueHelper, Catalog catalog, WalkData.Loaded walk, Executor executor)
	{
		this.client = client;
		this.clueHelper = clueHelper;
		this.catalog = catalog;
		this.walk = walk;
		this.executor = executor;
	}

	/** Client thread: times the scene BFS here, then plans the steps on the executor. */
	void run()
	{
		PlayerModel model = clueHelper.getPlayerModel();
		PlayerSnapshot player = model.getSnapshot();
		PlanStart start = clueHelper.getRoutes().getStart();
		if (player == null || model.getRuneModel() == null || start == null || catalog == null || walk == null)
		{
			log.info("{} not ready: snapshot, start, catalog or walk data missing", TAG);
			return;
		}
		AvailabilityEngine engine = new AvailabilityEngine(model.getRuneModel(), id -> "item " + id);
		timeBfs(player, engine, start);
		executor.execute(() -> planAll(player, engine, start));
	}

	private void timeBfs(PlayerSnapshot player, AvailabilityEngine engine, PlanStart start)
	{
		boolean[] rowOpen = new WalkGates(walk.getTable(), player, engine, AvailabilityService.nowMinutes()).rowOpen();
		long[] capture = new long[BFS_RUNS];
		long[] bfs = new long[BFS_RUNS];
		int reached = 0;
		for (int i = 0; i < BFS_RUNS; i++)
		{
			long t0 = System.nanoTime();
			SceneBfs.Scene scene = SceneBfs.Scene.capture(client.getTopLevelWorldView());
			long t1 = System.nanoTime();
			SceneBfs.Result result = scene == null ? null : SceneBfs.run(scene, start.getTile(), walk.getTable(), rowOpen);
			bfs[i] = System.nanoTime() - t1;
			capture[i] = t1 - t0;
			reached = result == null ? 0 : result.reachedTiles();
		}
		Arrays.sort(capture);
		Arrays.sort(bfs);
		log.info("{} scene at {}: capture median {} ms, BFS median {} ms (max {} ms), {} tiles reached", TAG, start.getTile(),
			ms(capture[BFS_RUNS / 2]), ms(bfs[BFS_RUNS / 2]), ms(bfs[BFS_RUNS - 1]), reached);
	}

	private void planAll(PlayerSnapshot player, AvailabilityEngine engine, PlanStart start)
	{
		Map<String, Step> steps;
		try
		{
			steps = DriftSteps.read();
		}
		catch (IOException e)
		{
			log.warn("{} drift steps unreadable", TAG, e);
			return;
		}
		for (String key : STEPS)
		{
			Step step = steps.get(key);
			Plan plan = Planner.plan(PlanInput.builder()
				.step(step)
				.player(player)
				.engine(engine)
				.nowMinutes(AvailabilityService.nowMinutes())
				.catalog(catalog)
				.table(walk.getTable())
				.noTeleport(walk.getNoTeleport())
				.start(start)
				.settings(PlannerSettings.builder().build())
				.build());
			log.info("{} {} {}: {} ms, {} labels, {} searches{}", TAG, key, step.getTargets(), ms(plan.getSearchNanos()), plan.getLabels(),
				plan.getSearches(), plan.getNote() == null ? "" : ", " + plan.getNote());
			line("best", plan.getBest());
			line("same book", plan.getSameBook());
			plan.getAlternatives().forEach(route -> line("alt", route));
		}
	}

	private static void line(String label, Route route)
	{
		if (route != null)
		{
			log.info("{}   {} {}t (rank {}): {} {}", TAG, label, route.getTicks(), (route.getRankHalfTicks() + 1) / 2, route.describe(),
				route.getSpent());
		}
	}

	private static String ms(long nanos)
	{
		return String.format("%.2f", nanos / 1e6);
	}
}
