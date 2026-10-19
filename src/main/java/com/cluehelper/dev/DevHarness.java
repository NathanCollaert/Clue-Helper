package com.cluehelper.dev;

import com.cluehelper.link.ClueLink;
import com.cluehelper.link.StepChange;
import com.cluehelper.step.Step;
import com.cluehelper.ui.PanelState;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.callback.ClientThread;

/** Everything the plugin adds in developer mode, and nothing outside it. */
@Slf4j
@Singleton
public class DevHarness
{
	private static final int STEP_LOG_LINES = 5;

	private final Client client;
	private final ClientThread clientThread;
	private final SelfTestRunner selfTests;
	private final StepLogger stepLogger;
	private final BuiltInSelfTests builtInSelfTests;
	private final ClueLink clueLink;
	private final StepDump stepDump;
	private final List<Runnable> unregister = new ArrayList<>();
	private volatile DevCommands commands;

	@Inject
	DevHarness(Client client, ClientThread clientThread, SelfTestRunner selfTests, StepLogger stepLogger,
		BuiltInSelfTests builtInSelfTests, ClueLink clueLink, Gson gson)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.selfTests = selfTests;
		this.stepLogger = stepLogger;
		this.builtInSelfTests = builtInSelfTests;
		this.clueLink = clueLink;
		this.stepDump = new StepDump(clueLink, gson);
	}

	/**
	 * @param shown the state the panel shows
	 * @param expected the state the plugin's signals call for
	 */
	public void start(Supplier<PanelState> shown, Supplier<PanelState> expected)
	{
		DevCommands c = new DevCommands(this::reply);
		c.register("selftest", "run the in-client self-tests", args -> runSelfTests());
		c.register("steplog", "[clear] show or clear this profile's S12 step log", this::stepLog);
		c.register("step", "show the current step and log it as a replay fixture line", args -> dumpStep());
		for (SelfTest test : builtInSelfTests.create(shown, expected))
		{
			unregister.add(selfTests.register(test));
		}
		stepLogger.setEnabled(true);
		unregister.add(clueLink.addListener(this::onStepChange));
		commands = c;
		log.info("developer mode: ::ch-help lists the dev commands");
	}

	public void stop()
	{
		commands = null;
		stepLogger.setEnabled(false);
		unregister.forEach(Runnable::run);
		unregister.clear();
	}

	public void onCommandExecuted(CommandExecuted event)
	{
		DevCommands c = commands;
		if (c != null)
		{
			c.dispatch(event.getCommand(), event.getArguments());
		}
	}

	public SelfTestRunner getSelfTests()
	{
		return selfTests;
	}

	public StepLogger getStepLogger()
	{
		return stepLogger;
	}

	private void onStepChange(StepChange change, Step step)
	{
		log.info("{} {} {}", StepDump.TAG, change, step == null ? "" : step.getKey());
		if (step != null)
		{
			StepDump.lines(step).forEach(line -> log.info("{}   {}", StepDump.TAG, line));
		}
		log.info(stepDump.fixtureLine(client.getTickCount(), change, step));
		if (change == StepChange.NEW)
		{
			stepLogger.record(step.getTier(), step.getType(), step.getKey());
		}
	}

	private void dumpStep()
	{
		stepDump.describe(this::reply);
		Step step = clueLink.getCurrentStep();
		if (step != null)
		{
			log.info(stepDump.fixtureLine(client.getTickCount(), StepChange.UNCHANGED, step));
		}
	}

	private void runSelfTests()
	{
		boolean started = selfTests.run(report ->
		{
			reply("Self-test: " + report);
			report.failures().forEach(failure -> reply("  failed: " + failure));
		});
		reply(started ? "Self-test started: " + selfTests.testNames().size() + " tests" : "A self-test run is already going");
	}

	private void stepLog(String[] args)
	{
		if (args.length > 0 && args[0].equalsIgnoreCase("clear"))
		{
			stepLogger.clear();
			reply("Step log cleared");
			return;
		}
		List<StepLog.Entry> entries = stepLogger.entries();
		reply("Step log: " + entries.size() + " entries");
		for (StepLog.Entry entry : entries.subList(Math.max(0, entries.size() - STEP_LOG_LINES), entries.size()))
		{
			reply("  " + entry);
		}
	}

	private void reply(String message)
	{
		log.info("[CH-DEV] {}", message);
		clientThread.invoke(() ->
		{
			// Core's Loot Tracker reads the local player on every chat message, which throws when logged out.
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
			}
		});
	}
}
