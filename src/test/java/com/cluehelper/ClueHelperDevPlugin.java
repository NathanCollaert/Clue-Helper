package com.cluehelper;

import com.cluehelper.campaign.Campaign;
import com.cluehelper.campaign.CampaignRecorder;
import com.cluehelper.campaign.Outcome;
import com.cluehelper.dev.DevHarness;
import com.cluehelper.dev.SelfTest;
import com.cluehelper.dev.SelfTestRun;
import com.cluehelper.guide.ActWatcher;
import com.cluehelper.guide.FairyDials;
import com.cluehelper.guide.SubStep;
import com.cluehelper.link.StepChange;
import com.cluehelper.memory.StashMemory;
import com.cluehelper.player.Position;
import com.cluehelper.player.PositionRules;
import com.cluehelper.route.GearPieces;
import com.cluehelper.route.LoadedScene;
import com.cluehelper.route.Plan;
import com.cluehelper.route.WalkData;
import com.cluehelper.transport.CatalogData;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.SceneFiles;
import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.CoreClues;
import com.cluehelper.step.Stage;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepResolver;
import com.cluehelper.transport.AvailabilitySet;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.ui.BoxState;
import com.cluehelper.ui.GuideBox;
import com.cluehelper.ui.GuideOverlay;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.WorldView;
import net.runelite.api.WallObject;
import net.runelite.api.Tile;
import net.runelite.api.ObjectComposition;
import net.runelite.api.GameObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Dev launcher only: self-tests that need files or drive other plugins, which the plugin jar must not do.
 * With -Dcluehelper.selftest=true (gradlew dev -Pselftest) it runs ::ch-selftest once the UI has settled,
 * and again a few ticks after the first login.
 */
@Slf4j
@PluginDescriptor(name = "Clue Helper Dev", description = "Dev only: game frames and plugin toggle checks")
public class ClueHelperDevPlugin extends Plugin
{
	private static final String TAG = "[CH-TEST]";
	private static final File SNAPSHOTS = new File("build/selftest");
	private static final File STEP_FRAMES = new File("build/steps");
	private static final File PLAYER_FRAMES = new File("build/player");
	private static final File LANDING_FRAMES = new File("build/landings");
	private static final String LANDING_TAG = "[CH-LAND]";
	private static final File ROUTE_FRAMES = new File("build/routes");
	private static final File SCENES = new File("build/scenes");
	private static final File GUIDE_FRAMES = new File("build/guide");
	/** About 5 s, to see highlights between sub-step changes. */
	private static final int LIVE_FRAME_TICKS = 16;
	private static final int OBJECT_DUMP_TILES = 30;
	private static final String CHALLENGE_COMPLETED = "Skill challenge completed.";
	private static final int MOST_LIVE_FRAMES = 200;
	private static final int MOST_SCENES = 12;
	private static final int LANDING_CANDIDATES = 2;
	private static final int FRAME_NAME_KEY_CHARS = 50;
	private static final int PLUGIN_DELAY_MS = 2000;
	private static final int AUTO_RUN_AFTER_TICKS = 5;
	/** Hub plugins add their sidebar tabs late; the first run waits for them. */
	private static final int AUTO_RUN_AFTER_START_MS = 15_000;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private DrawManager drawManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private StepResolver stepResolver;

	@Inject
	private FairyDials fairyDials;

	@Inject
	private CatalogData catalogData;

	@Inject
	private WalkData walkData;

	/** For the blocking config save, which must not run on the Swing thread. */
	@Inject
	private ScheduledExecutorService executor;

	private ClueHelperPlugin clueHelper;
	/** Built in Clue Helper's own injector, so it shares the plugin's services. */
	private DevHarness harness;
	private final List<Runnable> unregister = new ArrayList<>();
	private boolean autoRunPending = Boolean.getBoolean("cluehelper.selftest");
	private int loggedInTicks;
	private int snapshotCount;
	private int stepFrameCount;
	private int playerFrameCount;
	private int lastAvailableCount = -1;
	private int landingFrameCount;
	private int routeFrameCount;
	private int guideFrameCount;
	private String lastGuideText;
	private int liveFrameCount;
	private String lastPlannedStep;
	private boolean sampled;
	private final Set<String> recordedScenes = new HashSet<>();
	private WorldPoint lastTile;
	private MockClues mockClues;
	private Campaign campaign;
	private boolean campaignResumed;
	private int campaignTicks;
	/** Campaign recordings are written and replayed here, off the client thread. */
	private ExecutorService campaignFiles;
	/** PNG encoding takes seconds of CPU a minute: off RuneLite's shared executor, which other plugins' tasks wait on. */
	private ExecutorService frameWriter;

	@Override
	protected void startUp()
	{
		clueHelper = find(ClueHelperPlugin.class);
		harness = clueHelper.getInjector().getInstance(DevHarness.class);
		harness.start(() -> clueHelper.getGuideOverlay().getShown().getState(), clueHelper::boxState);
		frameWriter = Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "clue-helper-dev-frames");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
		clearOldPngs(SNAPSHOTS);
		clearOldPngs(STEP_FRAMES);
		clearOldPngs(PLAYER_FRAMES);
		clearOldPngs(LANDING_FRAMES);
		clearOldPngs(ROUTE_FRAMES);
		clearOldPngs(GUIDE_FRAMES);
		clearOldPngs(PohTrace.FRAMES);
		clearOldPngs(HotColdTrace.FRAMES);
		SCENES.mkdirs();
		unregister.add(clueHelper.getClueLink().addListener(this::saveStepFrame));
		unregister.add(clueHelper.getAvailability().addListener(this::savePlayerFrame));
		unregister.add(clueHelper.getRoutes().addListener(this::onPlanned));
		unregister.add(clueHelper.getGuide().addListener(this::saveGuideFrames));
		unregister.add(harness.getSelfTests().register(new CoreToggleTest()));
		unregister.add(harness.getSelfTests().register(new RestartTest()));
		BankTrace bankTrace = new BankTrace(client, clueHelper.getBankMemory(), clueHelper.getGuide(),
			file -> requestFrame(frame -> frameWriter.execute(() -> writeFrame(frame, file))));
		eventBus.register(bankTrace);
		unregister.add(() -> eventBus.unregister(bankTrace));
		PohTrace pohTrace = new PohTrace(client, clueHelper.getPoh(), clueHelper.getGuide(), clueHelper.getMenuLines(),
			file -> requestFrame(frame -> frameWriter.execute(() -> writeFrame(frame, file))));
		eventBus.register(pohTrace);
		unregister.add(() ->
		{
			eventBus.unregister(pohTrace);
			pohTrace.stop();
		});
		NetworkTrace networkTrace = new NetworkTrace(client, clueHelper.getSpiritTrees(), clueHelper.getFairyRings(),
			file -> requestFrame(frame -> frameWriter.execute(() -> writeFrame(frame, file))));
		eventBus.register(networkTrace);
		unregister.add(() ->
		{
			eventBus.unregister(networkTrace);
			networkTrace.stop();
		});
		FairyDialTrace dialTrace = new FairyDialTrace(client, clueHelper.getMenuLines(), fairyDials,
			file -> requestFrame(frame -> frameWriter.execute(() -> writeFrame(frame, file))));
		eventBus.register(dialTrace);
		overlayManager.add(dialTrace);
		unregister.add(() ->
		{
			eventBus.unregister(dialTrace);
			overlayManager.remove(dialTrace);
		});
		HotColdTrace hotColdTrace = new HotColdTrace(client, clueHelper.getHotCold(), name ->
			requestFrame(frame -> frameWriter.execute(() -> writeFrame(frame, new File(HotColdTrace.FRAMES, name + "-game.png")))));
		unregister.add(hotColdTrace::stop);
		PlanTrace planTrace = new PlanTrace(client, clueHelper.getRoutes());
		unregister.add(planTrace::stop);
		addDevPanel();
		log.info("{} dev plugin up; auto-run: {}", TAG, autoRunPending);
		if (autoRunPending)
		{
			later(AUTO_RUN_AFTER_START_MS, this::postSelfTest);
		}
	}

	private void addDevPanel()
	{
		DevPanel[] panel = new DevPanel[1];
		mockClues = new MockClues(client, clueHelper.getClueLink()::getCore, stepResolver,
			description -> SwingUtilities.invokeLater(() -> panel[0].showMock(description)),
			ids -> SwingUtilities.invokeLater(() -> panel[0].fillList(ids)));
		campaign = campaign(text -> SwingUtilities.invokeLater(() -> panel[0].showCampaign(text)));
		unregister.add(clueHelper.getRoutes().addInputListener(campaign::onPlan));
		DevPanel.CampaignControls campaignControls = new DevPanel.CampaignControls()
		{
			@Override
			public void finish()
			{
				campaign.finish();
			}

			@Override
			public void actDone()
			{
				clientThread.invokeLater(ClueHelperDevPlugin.this::fakeActDone);
			}

			@Override
			public void bug(String note)
			{
				campaign.bug(note);
			}

			@Override
			public void previous()
			{
				campaign.previous();
			}

			@Override
			public void next()
			{
				campaign.next();
			}

			@Override
			public void stop()
			{
				campaign.stop();
			}
		};
		DevPanel.MockControls controls = new DevPanel.MockControls()
		{
			@Override
			public void random(String tier, String type)
			{
				clientThread.invokeLater(() -> mockClues.random(tier, type));
			}

			@Override
			public void randomList(String tier, String type)
			{
				clientThread.invokeLater(() -> mockClues.randomList(tier, type));
			}

			@Override
			public void walk(List<String> ids, int by)
			{
				clientThread.invokeLater(() -> mockClues.walk(ids, by));
			}

			@Override
			public void clear()
			{
				clientThread.invokeLater(mockClues::clear);
			}
		};
		List<DevPanel.Action> tools = List.of(
			command("Routes to the log", "ch-routes"),
			command("Guide sub-steps", "ch-guide"),
			command("Step as a fixture line", "ch-step"),
			command("Player snapshot", "ch-player"),
			command("Route graph overlay", "ch-overlay"),
			new DevPanel.Action("Objects near the step", () -> clientThread.invokeLater(this::dumpObjectsNearStep)),
			new DevPanel.Action("Skill challenge completed", () -> clientThread.invokeLater(this::completeChallenge)),
			command("Sample routes", "ch-sample"),
			command("Self-tests", "ch-selftest"));
		panel[0] = new DevPanel(campaignControls, controls, levels -> clientThread.invokeLater(() -> clueHelper.getPlayerModel().overrideLevels(levels)), tools);
		NavigationButton button = NavigationButton.builder()
			.tooltip("Clue Helper dev")
			.icon(DevPanel.icon())
			.priority(Integer.MAX_VALUE)
			.panel(panel[0])
			.build();
		clientToolbar.addNavigation(button);
		unregister.add(() -> clientToolbar.removeNavigation(button));
	}

	private Campaign campaign(Consumer<String> show)
	{
		campaignFiles = Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "clue-helper-dev-campaign");
			thread.setDaemon(true);
			return thread;
		});
		Map<String, MockClues.Mock> mocks = new HashMap<>();
		CampaignRecorder recorder = new CampaignRecorder(client, clueHelper.getInjector().getInstance(StashMemory.class),
			clueHelper.getInjector().getInstance(GearPieces.class), clueHelper.getBankMemory(), clueHelper.getPlayerModel()::getRuneModel);
		return new Campaign(clientThread, recorder,
			() -> campaignSteps(mocks),
			new Campaign.Clues()
			{
				@Override
				public void mock(Campaign.CampaignStep step)
				{
					mockClues.mock(mocks.get(step.getKey()), "campaign");
				}

				@Override
				public void clear()
				{
					mockClues.clear();
				}
			},
			() -> Outcome.guideLines(clueHelper.getGuide().getSteps()),
			this::saveCampaignScreenshot,
			show,
			campaignFiles);
	}

	/** Core's clues, one per step key, each with its place in core's lists. */
	private List<Campaign.CampaignStep> campaignSteps(Map<String, MockClues.Mock> mocks)
	{
		List<Campaign.CampaignStep> steps = new ArrayList<>();
		for (MockClues.Mock mock : mockClues.all())
		{
			if (mocks.putIfAbsent(mock.key, mock) != null)
			{
				continue;
			}
			try
			{
				steps.add(new Campaign.CampaignStep(mock.key, mock.tier, mock.type, mock.text, CoreClues.source(mock.clue), mock.target));
			}
			catch (ReflectiveOperationException e)
			{
				log.warn("{} can't place {} in core's lists", TAG, mock.key, e);
			}
		}
		return steps;
	}

	/** No frame while the bank PIN keypad is open; {@code then} runs either way. */
	private void saveCampaignScreenshot(java.nio.file.Path file, Runnable then)
	{
		if (client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0) != null)
		{
			then.run();
			return;
		}
		drawManager.requestNextFrameListener(frame -> frameWriter.execute(() ->
		{
			file.toFile().getParentFile().mkdirs();
			writeFrame(frame, file.toFile());
			then.run();
		}));
	}

	/** Every wall and game object with an option around the step's tile: what a door, gate or obstacle really is. */
	private void dumpObjectsNearStep()
	{
		SubStep current = clueHelper.getGuide().getCurrent();
		WorldPoint at = current == null ? null : current.getWaypoint() != null ? current.getWaypoint() : current.getGoal();
		WorldView view = client.getTopLevelWorldView();
		if (at == null || view == null)
		{
			log.info("{} objects: no step tile", TAG);
			return;
		}
		Tile[][][] tiles = view.getScene().getTiles();
		for (int dx = -OBJECT_DUMP_TILES; dx <= OBJECT_DUMP_TILES; dx++)
		{
			for (int dy = -OBJECT_DUMP_TILES; dy <= OBJECT_DUMP_TILES; dy++)
			{
				int x = at.getX() + dx - view.getBaseX();
				int y = at.getY() + dy - view.getBaseY();
				Tile tile = x < 0 || y < 0 || x >= tiles[at.getPlane()].length || y >= tiles[at.getPlane()][x].length ? null
					: tiles[at.getPlane()][x][y];
				if (tile == null)
				{
					continue;
				}
				WallObject wall = tile.getWallObject();
				if (wall != null)
				{
					logObject("wall", at.dx(dx).dy(dy), wall.getId(), wall.getConfig(), "orientation " + wall.getOrientationA() + "/" + wall.getOrientationB());
				}
				for (GameObject object : tile.getGameObjects())
				{
					if (object != null && object.getSceneMinLocation().equals(tile.getSceneLocation()))
					{
						logObject("game", at.dx(dx).dy(dy), object.getId(), object.getConfig(), "size " + object.sizeX() + "x" + object.sizeY());
					}
				}
			}
		}
		client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Objects near the step written to the log", null);
	}

	private void logObject(String kind, WorldPoint at, int id, int config, String shape)
	{
		ObjectComposition definition = client.getObjectDefinition(id);
		ObjectComposition shown = definition.getImpostorIds() != null ? definition.getImpostor() : null;
		ObjectComposition named = shown != null ? shown : definition;
		boolean usable = named.getActions() != null && java.util.Arrays.stream(named.getActions()).anyMatch(java.util.Objects::nonNull);
		if (!usable)
		{
			return;
		}
		log.info("{} object {} {} {} id {} shown {} name '{}' actions {} type {} {}", TAG, kind, at.getX(), at.getY(), id,
			shown == null ? -1 : shown.getId(), (shown != null ? shown : definition).getName(),
			java.util.Arrays.toString((shown != null ? shown : definition).getActions()), config & 0x1F, shape);
	}

	/**
	 * A mocked clue gets no reply from the game, so the act waiting for one never ends: a Sherlock task is marked done
	 * as the game's chat line would, Uri or a challenge item counts as arrived. STASH and gear acts end in the game
	 * itself, and a step's last act ends with the campaign's Finish, which hands out the next clue.
	 */
	private void fakeActDone()
	{
		Step step = clueHelper.getClueLink().getCurrentStep();
		SubStep current = clueHelper.getGuide().getCurrent();
		SubStep.Done done = current == null ? null : current.getDone();
		boolean taskLeft = step != null && ClueTypes.SKILL.equals(step.getType())
			&& step.getStages().stream().anyMatch(stage -> stage.getKind() == Stage.Kind.TASK);
		if (taskLeft)
		{
			completeChallenge();
		}
		else if (done == SubStep.Done.URI_HERE || done == SubStep.Done.CHALLENGE_ITEM)
		{
			markWatched(done == SubStep.Done.URI_HERE ? "uriTick" : "challengeTick");
		}
		else
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Nothing to fake for " + (done == null ? "this act" : done)
				+ ": the game ends STASH and gear acts, Finish ends the step", null);
			return;
		}
		log.info("{} act done faked: {}", TAG, taskLeft ? "skill challenge completed" : done);
	}

	/** The watcher saw its event this tick. */
	private void markWatched(String tickField)
	{
		try
		{
			java.lang.reflect.Field field = ActWatcher.class.getDeclaredField(tickField);
			field.setAccessible(true);
			field.setInt(clueHelper.getInjector().getInstance(ActWatcher.class), client.getTickCount());
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("ActWatcher has no " + tickField + " any more: update the dev panel's Act done", e);
		}
	}

	/**
	 * The game sends this only to a player carrying the real challenge scroll; a mocked Sherlock clue gets it from here,
	 * so the Clue Scroll plugin marks the task done.
	 */
	private void completeChallenge()
	{
		eventBus.post(new ChatMessage(null, ChatMessageType.GAMEMESSAGE, "", CHALLENGE_COMPLETED, null, 0));
		log.info("{} posted '{}'", TAG, CHALLENGE_COMPLETED);
	}

	private DevPanel.Action command(String label, String command)
	{
		return new DevPanel.Action(label, () -> clientThread.invokeLater(() -> eventBus.post(new CommandExecuted(command, new String[0]))));
	}

	@Override
	protected void shutDown()
	{
		harness.stop();
		frameWriter.shutdown();
		campaignFiles.shutdown();
		unregister.forEach(Runnable::run);
		unregister.clear();
	}

	/** Runs after the plugin's priority -1 handler has read the position. */
	@Subscribe(priority = -2)
	public void onGameTick(GameTick tick)
	{
		checkLanding();
		saveLiveFrame();
		resumeCampaignOnceLoggedIn();
		if (!autoRunPending || client.getGameState() != GameState.LOGGED_IN || ++loggedInTicks < AUTO_RUN_AFTER_TICKS)
		{
			return;
		}
		autoRunPending = false;
		postSelfTest();
	}

	/**
	 * Mocking or chat on the login screen breaks core's plugins, so the campaign loads a few ticks into the first login.
	 * The mocks read core through Clue Helper's link, which is only set while Clue Helper runs.
	 */
	private void resumeCampaignOnceLoggedIn()
	{
		if (!campaignResumed && client.getGameState() == GameState.LOGGED_IN && pluginManager.isPluginActive(clueHelper)
			&& ++campaignTicks >= AUTO_RUN_AFTER_TICKS)
		{
			campaignResumed = true;
			campaign.resume();
		}
	}

	private void checkLanding()
	{
		Position position = clueHelper.getPlayerModel().getPosition();
		WorldPoint tile = position.getTile();
		if (PositionRules.teleported(lastTile, tile))
		{
			logLanding(lastTile, tile, position.getKind());
		}
		lastTile = tile;
	}

	private void logLanding(WorldPoint from, WorldPoint to, Position.Kind kind)
	{
		StringBuilder line = new StringBuilder(LANDING_TAG).append(" t").append(client.getTickCount()).append(' ')
			.append(text(from)).append(" -> ").append(text(to)).append(" (").append(kind).append(')');
		clueHelper.getAvailability().getCatalog().stream()
			.filter(row -> row.getDestination() != null && row.getDestination().getPlane() == to.getPlane())
			.sorted(Comparator.comparingInt((CatalogRow row) -> row.getDestination().distanceTo2D(to)))
			.map(row -> row.getName() + " [" + row.getCategory() + "] " + text(row.getDestination()) + " d=" + row.getDestination().distanceTo2D(to))
			.distinct()
			.limit(LANDING_CANDIDATES)
			.forEach(candidate -> line.append(" | ").append(candidate));
		log.info(line.toString());
		String name = String.format("%03d-%d-%d-%d.png", ++landingFrameCount, to.getX(), to.getY(), to.getPlane());
		requestFrame(frame -> frameWriter.execute(() -> writeFrame(frame, new File(LANDING_FRAMES, name))));
	}

	/** Never a frame of the bank PIN keypad. */
	private void requestFrame(Consumer<Image> listener)
	{
		if (client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0) == null)
		{
			drawManager.requestNextFrameListener(listener);
		}
	}

	private static String text(WorldPoint tile)
	{
		return tile.getX() + " " + tile.getY() + " " + tile.getPlane();
	}

	private void postSelfTest()
	{
		log.info("{} auto-run: posting ::ch-selftest", TAG);
		clientThread.invokeLater(() -> eventBus.post(new CommandExecuted("ch-selftest", new String[0])));
	}

	private final class CoreToggleTest implements SelfTest
	{
		@Override
		public int order()
		{
			return 110;
		}

		@Override
		public String name()
		{
			return "core toggle drives the box";
		}

		@Override
		public void run(SelfTestRun run)
		{
			SwingUtilities.invokeLater(() ->
			{
				Plugin core = find(ClueScrollPlugin.class);
				if (!pluginManager.isPluginActive(core))
				{
					run.skip("core Clue Scroll is off already");
					run.done();
					return;
				}
				setPluginRunning(core, false);
				later(PLUGIN_DELAY_MS, () ->
				{
					GuideBox box = clueHelper.getGuideOverlay().getShown();
					run.check(box.getState() == BoxState.CORE_OFF, "box state " + box.getState() + ", expected CORE_OFF");
					run.check(box.isShown() == box.isCarryingClue(), "core-off box shown " + box.isShown()
						+ " with a clue carried " + box.isCarryingClue());
					frame("core-off");
					setPluginRunning(core, true);
					executor.execute(configManager::sendConfig);
					later(PLUGIN_DELAY_MS, () ->
					{
						run.check(pluginManager.isPluginActive(core), "core Clue Scroll running again");
						BoxState shown = clueHelper.getGuideOverlay().getShown().getState();
						run.check(shown == clueHelper.boxState(), "box shows " + shown + ", signals say " + clueHelper.boxState());
						run.check(shown != BoxState.CORE_OFF, "box left the core-off state");
						frame("core-on");
						run.done();
					});
				});
			});
		}
	}

	private final class RestartTest implements SelfTest
	{
		@Override
		public int order()
		{
			return 1000;
		}

		@Override
		public String name()
		{
			return "plugin restart";
		}

		@Override
		public void run(SelfTestRun run)
		{
			SwingUtilities.invokeLater(() ->
			{
				stopOrStart(clueHelper, false);
				later(PLUGIN_DELAY_MS, () ->
				{
					run.check(!overlayManager.anyMatch(GuideOverlay.class::isInstance), "box overlay removed on stop");
					stopOrStart(clueHelper, true);
					later(PLUGIN_DELAY_MS, () ->
					{
						run.check(overlayManager.anyMatch(GuideOverlay.class::isInstance), "box overlay back on start");
						BoxState shown = clueHelper.getGuideOverlay().getShown().getState();
						run.check(shown == clueHelper.boxState(), "box shows " + shown + " after the restart, signals say "
							+ clueHelper.boxState());
						List<String> names = harness.getSelfTests().testNames();
						run.check(names.size() == new HashSet<>(names).size(), "built-in tests registered once after the restart: " + names);
						run.done();
					});
				});
			});
		}
	}

	private void setPluginRunning(Plugin plugin, boolean running)
	{
		pluginManager.setPluginEnabled(plugin, running);
		stopOrStart(plugin, running);
	}

	private void stopOrStart(Plugin plugin, boolean start)
	{
		try
		{
			if (start)
			{
				pluginManager.startPlugin(plugin);
			}
			else
			{
				pluginManager.stopPlugin(plugin);
			}
		}
		catch (PluginInstantiationException e)
		{
			log.warn("{} could not {} {}", TAG, start ? "start" : "stop", plugin.getName(), e);
		}
	}

	private <T extends Plugin> T find(Class<T> type)
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (type.isInstance(plugin))
			{
				return type.cast(plugin);
			}
		}
		throw new IllegalStateException("no " + type.getSimpleName() + " loaded");
	}

	private void frame(String name)
	{
		File file = new File(SNAPSHOTS, String.format("%02d-%s.png", ++snapshotCount, name));
		requestFrame(frame -> frameWriter.execute(() -> writeFrame(frame, file)));
	}

	private static void clearOldPngs(File dir)
	{
		dir.mkdirs();
		File[] old = dir.listFiles((parent, name) -> name.endsWith(".png"));
		if (old != null)
		{
			for (File f : old)
			{
				f.delete();
			}
		}
	}

	/** Lets a run's reads be checked against core's overlay afterwards. */
	private void saveStepFrame(StepChange change, Step step)
	{
		String key = step == null ? "none" : step.getKey().replaceAll("[^a-zA-Z0-9]+", "-");
		String name = String.format("%03d-%s-%s.png", ++stepFrameCount, change.name().toLowerCase(Locale.ROOT),
			key.substring(0, Math.min(key.length(), FRAME_NAME_KEY_CHARS)));
		requestFrame(frame -> frameWriter.execute(() -> writeFrame(frame, new File(STEP_FRAMES, name))));
	}

	/** The recorded scenes are test resources for the walk checks. */
	private void onPlanned(Plan plan)
	{
		PlanStart start = clueHelper.getRoutes().getStart();
		LoadedScene scene = start == null || start.getWalk() == null ? null : start.getWalk().getScene();
		if (scene != null && start.getKind() == PlanStart.Kind.SCENE && recordedScenes.size() < MOST_SCENES)
		{
			String name = scene.getBaseX() + "_" + scene.getBaseY() + "_" + start.getTile().getPlane();
			if (recordedScenes.add(name))
			{
				SceneFiles.write(new File(SCENES, name + ".scene.gz"), scene, start.getTile());
				log.info("{} scene {} recorded at {}", TAG, name, text(start.getTile()));
			}
		}
		if (!sampled)
		{
			sampled = true;
			sample();
		}
		if (plan.getStepKey() == null || plan.getStepKey().equals(lastPlannedStep))
		{
			return;
		}
		lastPlannedStep = plan.getStepKey();
		String key = plan.getStepKey().replaceAll("[^a-zA-Z0-9]+", "-");
		String name = String.format("%03d-%s.png", ++routeFrameCount, key.substring(0, Math.min(key.length(), FRAME_NAME_KEY_CHARS)));
		requestFrame(frame -> frameWriter.execute(() -> writeFrame(frame, new File(ROUTE_FRAMES, name))));
	}

	private void saveLiveFrame()
	{
		SubStep current = clueHelper.getGuide().getCurrent();
		if (current == null || client.getTickCount() % LIVE_FRAME_TICKS != 0 || liveFrameCount >= MOST_LIVE_FRAMES)
		{
			return;
		}
		String name = String.format("live-%03d-t%d-%s-game.png", ++liveFrameCount, client.getTickCount(),
			current.getKind().name().toLowerCase(Locale.ROOT));
		requestFrame(frame -> frameWriter.execute(() -> writeFrame(frame, new File(GUIDE_FRAMES, name))));
	}

	private void saveGuideFrames()
	{
		SubStep current = clueHelper.getGuide().getCurrent();
		String text = current == null ? null : current.getKind() + " " + current.getText();
		if (text == null || text.equals(lastGuideText))
		{
			return;
		}
		lastGuideText = text;
		String name = String.format("%03d-%s", ++guideFrameCount, current.getKind().name().toLowerCase(Locale.ROOT));
		requestFrame(frame -> frameWriter.execute(() -> writeFrame(frame, new File(GUIDE_FRAMES, name + "-game.png"))));
	}

	private void sample()
	{
		new RouteSampler(client, clueHelper, catalogData.get().getNow(null), walkData.get().getNow(null), executor).run();
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		harness.onCommandExecuted(event);
		if (event.getCommand().equalsIgnoreCase("ch-sample"))
		{
			sample();
		}
		else if (event.getCommand().equalsIgnoreCase(MockClues.COMMAND))
		{
			// Commands arrive inside the chat script, and core's clue providers run quest scripts, which can't nest.
			clientThread.invokeLater(() -> mockClues.run(event.getArguments()));
		}
	}

	private void savePlayerFrame(AvailabilitySet carried)
	{
		int available = carried.available().size();
		if (available == lastAvailableCount)
		{
			return;
		}
		lastAvailableCount = available;
		String name = String.format("%03d-v%d-available-%d.png", ++playerFrameCount, carried.getSnapshotVersion(), available);
		requestFrame(frame -> frameWriter.execute(() -> writeFrame(frame, new File(PLAYER_FRAMES, name))));
	}

	private static void writeFrame(Image frame, File file)
	{
		BufferedImage image = new BufferedImage(frame.getWidth(null), frame.getHeight(null), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.drawImage(frame, 0, 0, null);
		g.dispose();
		try
		{
			ImageIO.write(image, "png", file);
			log.info("{} step frame {}", TAG, file.getAbsolutePath());
		}
		catch (Exception e)
		{
			log.warn("{} step frame {} failed", TAG, file, e);
		}
	}

	private static void later(int ms, Runnable action)
	{
		Timer timer = new Timer(ms, e -> action.run());
		timer.setRepeats(false);
		timer.start();
	}
}
