package com.cluehelper.dev;

import com.cluehelper.guide.StepGuide;
import com.cluehelper.guide.SubStep;
import com.cluehelper.link.ClueLink;
import com.cluehelper.link.StepChange;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.route.Plan;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.route.WalkData;
import com.cluehelper.step.Step;
import com.cluehelper.transport.AvailabilityService;
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
import net.runelite.client.ui.overlay.OverlayManager;

/** Everything the plugin adds in developer mode, and nothing outside it. */
@Slf4j
@Singleton
public class DevHarness
{
	private static final int STEP_LOG_LINES = 5;
	private static final String GUIDE_TAG = "[CH-GUIDE]";

	private final Client client;
	private final ClientThread clientThread;
	private final SelfTestRunner selfTests;
	private final StepLogger stepLogger;
	private final BuiltInSelfTests builtInSelfTests;
	private final ClueLink clueLink;
	private final StepDump stepDump;
	private final PlayerDump playerDump;
	private final AvailabilityService availability;
	private final RoutePlanner routes;
	private final RouteDump routeDump;
	private final StepGuide guide;
	private String lastGuideLine;
	private final RouteOverlay routeOverlay;
	private final OverlayManager overlayManager;
	private final List<Runnable> unregister = new ArrayList<>();
	private boolean overlayShown;
	private volatile DevCommands commands;

	@Inject
	DevHarness(Client client, ClientThread clientThread, SelfTestRunner selfTests, StepLogger stepLogger,
		BuiltInSelfTests builtInSelfTests, ClueLink clueLink, Gson gson, PlayerModel player,
		AvailabilityService availability, RoutePlanner routes, WalkData walkData, OverlayManager overlayManager, StepGuide guide)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.selfTests = selfTests;
		this.stepLogger = stepLogger;
		this.builtInSelfTests = builtInSelfTests;
		this.clueLink = clueLink;
		this.stepDump = new StepDump(clueLink, gson);
		this.playerDump = new PlayerDump(client, player, availability);
		this.availability = availability;
		this.routes = routes;
		this.routeDump = new RouteDump(routes);
		this.routeOverlay = new RouteOverlay(client, routes, walkData);
		this.overlayManager = overlayManager;
		this.guide = guide;
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
		c.register("player", "show the player snapshot, castable spells and A_carried", args ->
			playerDump.describe(this::reply, line -> log.info("{}   {}", PlayerDump.TAG, line)));
		c.register("routes", "show the current step's routes; every candidate goes to the log", args ->
			routeDump.describe(this::reply, line -> log.info("{}   {}", RouteDump.TAG, line)));
		c.register("guide", "show the followed route's sub-steps and what is highlighted", args -> dumpGuide());
		c.register("overlay", "toggle the route graph overlay: walk-table nodes, scene BFS walks, origin class", args -> toggleOverlay());
		for (SelfTest test : builtInSelfTests.create(shown, expected))
		{
			unregister.add(selfTests.register(test));
		}
		stepLogger.setEnabled(true);
		unregister.add(clueLink.addListener(this::onStepChange));
		unregister.add(availability.addListener(playerDump::onJudged));
		unregister.add(routes.addListener(this::onPlanned));
		unregister.add(guide.addListener(this::onGuideChanged));
		commands = c;
		log.info("developer mode: ::ch-help lists the dev commands");
	}

	public void stop()
	{
		commands = null;
		if (overlayShown)
		{
			toggleOverlay();
		}
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

	private void onPlanned(Plan plan)
	{
		log.info("{} {}", RouteDump.TAG, routeDump.header(plan));
		routeDump.summary(plan).forEach(line -> log.info("{}   {}", RouteDump.TAG, line));
	}

	/** One line per change of sub-step or highlight, so a live run can be followed in the log. */
	private void onGuideChanged()
	{
		String line = guideLine();
		if (!line.equals(lastGuideLine))
		{
			lastGuideLine = line;
			log.info("{} t{} {}", GUIDE_TAG, client.getTickCount(), line);
		}
	}

	private String guideLine()
	{
		SubStep current = guide.getCurrent();
		if (current == null)
		{
			return "no route";
		}
		return String.format("%d/%d %s %s | waypoint %s | items %s | widgets %s | route %s",
			guide.getSteps().indexOf(current) + 1, guide.getSteps().size(), current.getKind(), current.getText(),
			current.getWaypoint(), guide.getHighlightItems(), guide.getHighlightSpellWidget() + "/" + guide.getHighlightMenuWidget(),
			guide.getRoute() == null ? "-" : guide.getRoute().describe());
	}

	private void dumpGuide()
	{
		reply("Guide: " + guideLine());
		List<SubStep> steps = guide.getSteps();
		for (int i = 0; i < steps.size(); i++)
		{
			SubStep step = steps.get(i);
			reply(String.format("  %s%d %s: %s%s", step == guide.getCurrent() ? "> " : "", i + 1, step.getKind(), step.getText(),
				step.getDetail() == null ? "" : " (" + step.getDetail() + ")"));
		}
		guide.getMapWaypoints().forEach((tile, label) -> log.info("{}   map {} {}", GUIDE_TAG, tile, label));
	}

	private void toggleOverlay()
	{
		overlayShown = !overlayShown;
		if (overlayShown)
		{
			overlayManager.add(routeOverlay);
		}
		else
		{
			overlayManager.remove(routeOverlay);
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
