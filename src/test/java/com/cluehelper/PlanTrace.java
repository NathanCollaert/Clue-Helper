package com.cluehelper;

import com.cluehelper.route.Leg;
import com.cluehelper.route.Plan;
import com.cluehelper.route.PlanInput;
import com.cluehelper.route.PlanRecording;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.Route;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.route.SceneFiles;
import com.cluehelper.route.WalkCosts;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;

/**
 * Each plan slower than {@link #SLOW_MILLIS}, and each that drops a short walk for a teleport (the step's own walk
 * suddenly found long), with what it was planned from, as a {@link PlanRecording} in {@link #PLANS} (its scene beside it),
 * which {@code PlanReplay} plans again offline. Nothing while the bank PIN keypad is open.
 */
@Slf4j
final class PlanTrace
{
	static final String TAG = "[CH-PLANREC]";
	static final File PLANS = new File("build/plans");
	private static final long SLOW_MILLIS = 300;
	private static final long NANOS_PER_MILLI = 1_000_000;
	private static final int MOST_RECORDINGS = 40;
	private static final int SHORT_WALK_HALF_TICKS = 2 * 40;

	private final Client client;
	private final Gson gson = new Gson();
	private final Runnable unregister;
	private int recorded;
	private String shortWalkStepKey;

	PlanTrace(Client client, RoutePlanner routes)
	{
		this.client = client;
		PLANS.mkdirs();
		unregister = routes.addInputListener(this::record);
	}

	void stop()
	{
		unregister.run();
	}

	private void record(Plan plan, PlanInput input)
	{
		long millis = plan.getSearchNanos() / NANOS_PER_MILLI;
		boolean leftShortWalk = leftShortWalk(plan);
		if (millis < SLOW_MILLIS && !leftShortWalk || recorded >= MOST_RECORDINGS || pinOpen() || input.getStep() == null)
		{
			return;
		}
		String name = String.format("%03d-%s%s", ++recorded, leftShortWalk ? "left-walk-" : "", slug(input.getStep().getKey()));
		WalkCosts walk = input.getStart().getWalk();
		String sceneFile = null;
		if (walk != null && walk.getScene() != null)
		{
			sceneFile = name + ".scene.gz";
			SceneFiles.write(new File(PLANS, sceneFile), walk.getScene(), input.getStart().getTile());
		}
		PlanRecording recording = PlanRecording.of(plan, input, sceneFile);
		try (Writer out = new FileWriter(new File(PLANS, name + ".json"), StandardCharsets.UTF_8))
		{
			out.write(gson.toJson(recording));
			log.info("{} {} took {} ms, {} labels: recorded", TAG, name, millis, plan.getLabels());
		}
		catch (IOException e)
		{
			log.warn("{} could not record {}", TAG, name, e);
		}
	}

	/** The last plan for this step was a short walk and this one travels instead. */
	private boolean leftShortWalk(Plan plan)
	{
		Route best = plan.getBest();
		boolean walkOnly = best != null && best.getLegs().stream().allMatch(leg -> leg.getKind() == Leg.Kind.WALK);
		boolean left = best != null && !walkOnly && plan.getStepKey() != null && plan.getStepKey().equals(shortWalkStepKey);
		shortWalkStepKey = walkOnly && best.getHalfTicks() <= SHORT_WALK_HALF_TICKS ? plan.getStepKey() : null;
		return left;
	}

	private static String slug(String key)
	{
		String slug = key.toLowerCase().replaceAll("[^a-z0-9]+", "-");
		return slug.substring(0, Math.min(40, slug.length()));
	}

	private boolean pinOpen()
	{
		return client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0) != null;
	}
}
