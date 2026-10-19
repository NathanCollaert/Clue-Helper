package com.cluehelper.dev;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.link.ClueLink;
import com.cluehelper.memory.MemorySlot;
import com.cluehelper.memory.MemoryStore;
import com.cluehelper.route.PlannerRunner;
import com.cluehelper.step.ClueFacts;
import com.cluehelper.step.CoreClueReader;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepKey;
import com.cluehelper.ui.PanelState;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;
import net.runelite.client.plugins.PluginManager;

/** The self-tests every developer-mode run has. Later blocks add their own next to these. */
@Singleton
class BuiltInSelfTests
{
	private static final String ROUND_TRIP_KEY = "devSelfTest";

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final PluginManager pluginManager;
	private final ClueHelperConfig config;
	private final ClueLink core;
	private final MemoryStore memory;
	private final MemorySlot<String> roundTrip;

	@Inject
	BuiltInSelfTests(Client client, ClientThread clientThread, ScheduledExecutorService executor,
		PluginManager pluginManager, ClueHelperConfig config, ClueLink core, MemoryStore memory)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.pluginManager = pluginManager;
		this.config = config;
		this.core = core;
		this.memory = memory;
		this.roundTrip = memory.slot(ROUND_TRIP_KEY, String.class, () -> null);
	}

	/**
	 * @param shown the state the panel shows
	 * @param expected the state the plugin's signals call for
	 */
	List<SelfTest> create(Supplier<PanelState> shown, Supplier<PanelState> expected)
	{
		return Arrays.asList(
			SelfTest.of("config", this::checkConfig),
			SelfTest.of("core link", this::checkCoreLink),
			SelfTest.of("core link reads a clue", this::checkClueRead),
			SelfTest.of("panel state", run ->
				run.check(shown.get() == expected.get(), "panel shows " + shown.get() + ", signals say " + expected.get())),
			SelfTest.of("memory store", this::checkMemoryRoundTrip),
			new PlannerRunnerTest());
	}

	private void checkConfig(SelfTestRun run)
	{
		int routes = config.routeCount();
		run.check(routes >= 1 && routes <= 5, "routeCount " + routes + " is within 1-5");
		run.check(config.consumablePenalty() >= 0 && config.capeUsePenalty() >= 0 && config.strandPenalty() >= 0,
			"penalties are not negative");
		run.check(config.avoidWilderness() != null && config.spellbookChanges() != null && config.diggingDog() != null,
			"enum keys read through the config proxy: " + config.avoidWilderness() + ", "
				+ config.spellbookChanges() + ", " + config.diggingDog());
	}

	private void checkCoreLink(SelfTestRun run)
	{
		Plugin corePlugin = core.getCore();
		run.check(corePlugin != null, "core Clue Scroll plugin found");
		if (corePlugin != null)
		{
			boolean active = pluginManager.isPluginActive(corePlugin);
			run.check(core.isActive() == active, "active flag " + core.isActive() + " agrees with isPluginActive " + active);
		}
	}

	private void checkClueRead(SelfTestRun run)
	{
		ClueScroll clue = core.coreClue();
		if (clue == null || core.getCore() == null)
		{
			run.skip("the Clue Scroll plugin has no clue: read one first");
			return;
		}
		ClueFacts facts = CoreClueReader.facts(clue, core.getCore());
		String key = StepKey.of(facts);
		Step step = core.getCurrentStep();
		run.check(step != null && key.equals(step.getKey()), "current step " + (step == null ? null : step.getKey())
			+ " is core's clue " + key);
		if (step != null)
		{
			run.check(step.isKnown(), "the step data has " + step.getKey());
			run.check(!step.getTargets().isEmpty() || !step.getNpcs().isEmpty(), step.getTargets().size()
				+ " targets, " + step.getNpcs().size() + " NPCs");
		}
	}

	private void checkMemoryRoundTrip(SelfTestRun run)
	{
		if (!memory.hasProfile())
		{
			run.skip("no RuneScape profile yet: log in first");
			return;
		}
		String value = "self-test " + System.nanoTime();
		roundTrip.set(value);
		memory.reload();
		run.check(value.equals(roundTrip.get()), "value read back from the profile after a reload (game state "
			+ client.getGameState() + ")");
		roundTrip.clear();
		memory.reload();
		run.check(roundTrip.get() == null, "value gone from the profile after clear");
	}

	/** A runner of its own, so the test never supersedes a real planner job. */
	private final class PlannerRunnerTest implements SelfTest
	{
		@Override
		public String name()
		{
			return "planner runner";
		}

		@Override
		public void run(SelfTestRun run)
		{
			PlannerRunner runner = new PlannerRunner(executor, clientThread);
			AtomicBoolean supersededDelivered = new AtomicBoolean();
			runner.submit(() -> "superseded", result -> supersededDelivered.set(true));
			runner.submit(client::isClientThread, ranOnClientThread ->
			{
				run.check(!ranOnClientThread, "job ran off the client thread");
				run.check(client.isClientThread(), "result delivered on the client thread");
				run.check(!supersededDelivered.get(), "superseded job's result dropped");
				run.check(!runner.isWorking(), "runner idle after the latest result");
				run.done();
			});
		}
	}
}
