package com.cluehelper;

import com.cluehelper.hotcold.CoreSpots;
import com.cluehelper.hotcold.HotColdPlan;
import com.cluehelper.hotcold.HotColdService;
import com.cluehelper.hotcold.PlanInputs;
import com.cluehelper.hotcold.Probe;
import com.cluehelper.hotcold.ProbeJob;
import com.cluehelper.hotcold.ProbePlanner;
import com.cluehelper.hotcold.Reading;
import com.cluehelper.step.HotColdTier;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;

/**
 * {@code [CH-HOTCOLD]} lines, one per plan, and each plan with what it was planned from in {@link #DECISIONS}, which
 * {@code HotColdDecisionReplayTest} replays: every probe the plan was given, not only those it weighed. Nothing while the
 * bank PIN keypad is open.
 */
@Slf4j
public final class HotColdTrace
{
	static final String TAG = "[CH-HOTCOLD]";
	static final File FRAMES = new File("build/hotcold");
	static final File DECISIONS = new File(FRAMES, "decisions.jsonl");

	private final Client client;
	private final HotColdService hotCold;
	/** Plans the job's probes again for {@link #DECISIONS}, off the client thread. */
	private final ExecutorService recorder = Executors.newSingleThreadExecutor(runnable ->
	{
		Thread thread = new Thread(runnable, "hotcold-trace");
		thread.setDaemon(true);
		return thread;
	});
	private final Gson gson = new Gson();
	private final Consumer<String> saveFrames;
	private final Runnable unregister;
	private int planCount;

	/** @param saveFrames saves the game and the panel under the given name stem */
	HotColdTrace(Client client, HotColdService hotCold, Consumer<String> saveFrames)
	{
		this.client = client;
		this.hotCold = hotCold;
		this.saveFrames = saveFrames;
		FRAMES.mkdirs();
		unregister = hotCold.addListener(this::record);
	}

	void stop()
	{
		unregister.run();
		recorder.shutdown();
	}

	private void record()
	{
		ProbeJob job = hotCold.getPlanJob();
		HotColdPlan plan = hotCold.getPlan();
		if (pinOpen() || job == null || plan == null)
		{
			return;
		}
		log.info("{} t{} {} from {} readings, last {}: {}{}", TAG, client.getTickCount(), job.getStepKey(), job.getReadings().size(),
			job.getReadings().isEmpty() ? "-" : job.getReadings().get(job.getReadings().size() - 1), describe(plan), coreComparison(plan, job.getCore()));
		recorder.execute(() -> writeDecision(job, plan));
		saveFrames.accept(String.format("%03d-%s", ++planCount, plan.getAction().name().toLowerCase()));
	}

	private void writeDecision(ProbeJob job, HotColdPlan plan)
	{
		Decision decision = new Decision(job.getStepKey(), job.getTier() == HotColdTier.BEGINNER, job.getReadings(), PlanInputs.probes(job),
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
	}

	private boolean pinOpen()
	{
		return client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0) != null;
	}

	public static String describe(HotColdPlan planned)
	{
		StringBuilder line = new StringBuilder(planned.getAction().name()).append(' ').append(planned.getTile())
			.append(" kept ").append(planned.getKept().size());
		if (planned.getChoice() != null)
		{
			ProbePlanner.Choice choice = planned.getChoice();
			line.append(" probe ").append(choice.getProbe().getKind()).append(' ').append(choice.getProbe().getTicks()).append('t')
				.append(String.format(" %.2f bits cost %d", choice.getBits(), choice.getCostTicks()));
		}
		return line.toString();
	}

	/** The spots the grid kept that core has dropped. */
	private static String coreComparison(HotColdPlan planned, CoreSpots core)
	{
		List<String> dropped = planned.getKept().keySet().stream().filter(spot -> !core.getNames().contains(spot)).collect(Collectors.toList());
		return "; core " + core.getNames().size() + (core.isFelt() ? "" : " (reset)") + (dropped.isEmpty() ? "" : ", core dropped " + dropped);
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
