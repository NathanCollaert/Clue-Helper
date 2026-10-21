package com.cluehelper;

import com.cluehelper.dev.SelfTest;
import com.cluehelper.dev.SelfTestRun;
import com.cluehelper.guide.FairyDials;
import com.cluehelper.guide.SubStep;
import com.cluehelper.link.StepChange;
import com.cluehelper.player.Position;
import com.cluehelper.route.Plan;
import com.cluehelper.route.WalkData;
import com.cluehelper.transport.CatalogData;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.SceneBfs;
import com.cluehelper.route.SceneFiles;
import com.cluehelper.step.Step;
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
import java.util.HashSet;
import java.util.List;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
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
import net.runelite.client.ui.DrawManager;
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
	private static final int LIVE_FRAME_TICKS = 8;
	private static final int MOST_LIVE_FRAMES = 200;
	private static final int MOST_SCENES = 12;
	/** Farther than a run tick can carry you: a teleport, a ladder or a boat. */
	private static final int LANDING_JUMP_TILES = 4;
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
	private FairyDials fairyDials;

	@Inject
	private CatalogData catalogData;

	@Inject
	private WalkData walkData;

	/** For the blocking config save, which must not run on the Swing thread. */
	@Inject
	private ScheduledExecutorService executor;

	private ClueHelperPlugin clueHelper;
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

	@Override
	protected void startUp()
	{
		clueHelper = find(ClueHelperPlugin.class);
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
		unregister.add(clueHelper.getDevHarness().getSelfTests().register(new CoreToggleTest()));
		unregister.add(clueHelper.getDevHarness().getSelfTests().register(new RestartTest()));
		BankTrace bankTrace = new BankTrace(client, clueHelper.getBankMemory(), clueHelper.getGuide(),
			file -> requestFrame(frame -> executor.execute(() -> writeFrame(frame, file))));
		eventBus.register(bankTrace);
		unregister.add(() -> eventBus.unregister(bankTrace));
		PohTrace pohTrace = new PohTrace(client, clueHelper.getPoh(), clueHelper.getGuide(), clueHelper.getMenuLines(),
			file -> requestFrame(frame -> executor.execute(() -> writeFrame(frame, file))));
		eventBus.register(pohTrace);
		unregister.add(() ->
		{
			eventBus.unregister(pohTrace);
			pohTrace.stop();
		});
		NetworkTrace networkTrace = new NetworkTrace(client, clueHelper.getSpiritTrees(), clueHelper.getFairyRings(),
			file -> requestFrame(frame -> executor.execute(() -> writeFrame(frame, file))));
		eventBus.register(networkTrace);
		unregister.add(() ->
		{
			eventBus.unregister(networkTrace);
			networkTrace.stop();
		});
		FairyDialTrace dialTrace = new FairyDialTrace(client, clueHelper.getMenuLines(), fairyDials,
			file -> requestFrame(frame -> executor.execute(() -> writeFrame(frame, file))));
		eventBus.register(dialTrace);
		overlayManager.add(dialTrace);
		unregister.add(() ->
		{
			eventBus.unregister(dialTrace);
			overlayManager.remove(dialTrace);
		});
		HotColdTrace hotColdTrace = new HotColdTrace(client, clueHelper.getHotCold(), name ->
			requestFrame(frame -> executor.execute(() -> writeFrame(frame, new File(HotColdTrace.FRAMES, name + "-game.png")))));
		unregister.add(hotColdTrace::stop);
		log.info("{} dev plugin up; auto-run: {}", TAG, autoRunPending);
		if (autoRunPending)
		{
			later(AUTO_RUN_AFTER_START_MS, this::postSelfTest);
		}
	}

	@Override
	protected void shutDown()
	{
		unregister.forEach(Runnable::run);
		unregister.clear();
	}

	/** Runs after the plugin's priority -1 handler has read the position. */
	@Subscribe(priority = -2)
	public void onGameTick(GameTick tick)
	{
		checkLanding();
		saveLiveFrame();
		if (!autoRunPending || client.getGameState() != GameState.LOGGED_IN || ++loggedInTicks < AUTO_RUN_AFTER_TICKS)
		{
			return;
		}
		autoRunPending = false;
		postSelfTest();
	}

	private void checkLanding()
	{
		Position position = clueHelper.getPlayerModel().getPosition();
		WorldPoint tile = position.getTile();
		if (tile != null && lastTile != null
			&& (tile.getPlane() != lastTile.getPlane() || tile.distanceTo2D(lastTile) > LANDING_JUMP_TILES))
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
		requestFrame(frame -> executor.execute(() -> writeFrame(frame, new File(LANDING_FRAMES, name))));
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
						List<String> names = clueHelper.getDevHarness().getSelfTests().testNames();
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
		requestFrame(frame -> executor.execute(() -> writeFrame(frame, file)));
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
		requestFrame(frame -> executor.execute(() -> writeFrame(frame, new File(STEP_FRAMES, name))));
	}

	/** The recorded scenes are test resources for the scene BFS check. */
	private void onPlanned(Plan plan)
	{
		PlanStart start = clueHelper.getRoutes().getStart();
		if (start != null && start.getKind() == PlanStart.Kind.SCENE && recordedScenes.size() < MOST_SCENES)
		{
			SceneBfs.Scene scene = start.getScene().getScene();
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
		requestFrame(frame -> executor.execute(() -> writeFrame(frame, new File(ROUTE_FRAMES, name))));
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
		requestFrame(frame -> executor.execute(() -> writeFrame(frame, new File(GUIDE_FRAMES, name))));
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
		requestFrame(frame -> executor.execute(() -> writeFrame(frame, new File(GUIDE_FRAMES, name + "-game.png"))));
	}

	private void sample()
	{
		new RouteSampler(client, clueHelper, catalogData.get().getNow(null), walkData.get().getNow(null), executor).run();
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (event.getCommand().equalsIgnoreCase("ch-sample"))
		{
			sample();
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
		requestFrame(frame -> executor.execute(() -> writeFrame(frame, new File(PLAYER_FRAMES, name))));
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
