package com.cluehelper;

import com.cluehelper.hotcold.HotColdPlan;
import com.cluehelper.hotcold.HotColdService;
import com.cluehelper.hotcold.Probe;
import com.cluehelper.hotcold.ProbeJob;
import com.cluehelper.hotcold.Reading;
import com.cluehelper.step.HotColdTier;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;

/**
 * {@code [CH-HOTCOLD]} lines, and each plan with what it was planned from in {@link #DECISIONS}, which
 * {@code HotColdDecisionReplayTest} replays. Nothing while the bank PIN keypad is open.
 */
@Slf4j
final class HotColdTrace
{
	static final String TAG = "[CH-HOTCOLD]";
	static final File FRAMES = new File("build/hotcold");
	static final File DECISIONS = new File(FRAMES, "decisions.jsonl");

	private final Client client;
	private final Gson gson = new Gson();
	private final Consumer<String> saveFrames;
	private final Runnable unregister;
	private int planCount;

	/** @param saveFrames saves the game and the panel under the given name stem */
	HotColdTrace(Client client, HotColdService hotCold, Consumer<String> saveFrames)
	{
		this.client = client;
		this.saveFrames = saveFrames;
		FRAMES.mkdirs();
		Runnable untrace = hotCold.addTraceListener(this::line);
		Runnable unplan = hotCold.addPlanListener(this::record);
		unregister = () ->
		{
			untrace.run();
			unplan.run();
		};
	}

	void stop()
	{
		unregister.run();
	}

	private void line(String text)
	{
		if (!pinOpen())
		{
			log.info("{} t{} {}", TAG, client.getTickCount(), text);
		}
	}

	private void record(HotColdService.Planned planned)
	{
		if (pinOpen())
		{
			return;
		}
		ProbeJob job = planned.getJob();
		HotColdPlan plan = planned.getPlan();
		Decision decision = new Decision(job.getStepKey(), job.getTier() == HotColdTier.BEGINNER, job.getReadings(), plan.getConsidered(),
			job.getCosts().getWildernessPenaltyTicks(), plan.getAction().name(),
			plan.getTile() == null ? null : new int[]{plan.getTile().getX(), plan.getTile().getY(), plan.getTile().getPlane()});
		try (Writer out = new FileWriter(DECISIONS, StandardCharsets.UTF_8, true))
		{
			out.write(gson.toJson(decision) + "\n");
		}
		catch (IOException e)
		{
			log.warn("{} could not record a decision", TAG, e);
		}
		saveFrames.accept(String.format("%03d-%s", ++planCount, plan.getAction().name().toLowerCase()));
	}

	private boolean pinOpen()
	{
		return client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0) != null;
	}

	/** One line of {@link #DECISIONS}. */
	static final class Decision
	{
		final String stepKey;
		final boolean beginner;
		final List<Reading> readings;
		final List<Probe> probes;
		final int wildernessPenaltyTicks;
		final String action;
		final int[] tile;

		Decision(String stepKey, boolean beginner, List<Reading> readings, List<Probe> probes, int wildernessPenaltyTicks, String action, int[] tile)
		{
			this.stepKey = stepKey;
			this.beginner = beginner;
			this.readings = readings;
			this.probes = probes;
			this.wildernessPenaltyTicks = wildernessPenaltyTicks;
			this.action = action;
			this.tile = tile;
		}
	}
}
