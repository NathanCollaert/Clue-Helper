package com.cluehelper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.guide.SubStep;
import com.cluehelper.link.ClueLink;
import com.cluehelper.route.Plan;
import com.cluehelper.route.Route;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.step.Step;
import com.cluehelper.ui.GuideOverlay;
import com.cluehelper.ui.WaypointOverlay;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.events.ConfigChanged;
import org.slf4j.LoggerFactory;

/**
 * Everything the plugin shows and does, and what the player does, as one timeline per session in its own folder under
 * {@link #DIR} (with the session's frames beside it, named by tick), for
 * reading back afterwards to find what went wrong: a line per event (click, menu, game message, interface, animation,
 * clue change, plan) as it happens, and on every game tick a {@code state} line with each part of the plugin's output
 * that changed since the tick before (the guide's sub-step and list, the box, highlights, outlined objects and NPCs,
 * the path line, the plan, the clue step, the player). The session's first state line has every part, so the state at
 * any tick is the last value of each part before it. Also every warning and error a Clue Helper class logs, the
 * plugin's settings at the start and each change to them, and the moments the tester marked from the dev panel.
 *
 * <p>Nothing is kept from the bank PIN keypad but that it was clicked, and of chat only the game's own lines.
 */
@Slf4j
final class TimelineRecorder
{
	static final File DIR = new File(TimingLog.DIR.getParentFile(), "timeline");
	private static final Set<ChatMessageType> GAME_LINES = Set.of(ChatMessageType.GAMEMESSAGE, ChatMessageType.SPAM,
		ChatMessageType.MESBOX, ChatMessageType.ENGINE, ChatMessageType.DIALOG, ChatMessageType.CONSOLE);
	private static final String BOX_FIELD = "box";
	private static final String PLUGIN_LOGGERS = "com.cluehelper";

	private final Client client;
	private final StepGuide guide;
	private final ClueLink clueLink;
	private final RoutePlanner planner;
	private final WaypointOverlay waypoints;
	private final Gson gson;
	private final Field guideBox;
	private final GuideOverlay guideOverlay;
	private final Map<String, Supplier<Object>> parts = new LinkedHashMap<>();
	private final Map<String, String> lastParts = new HashMap<>();
	private final List<Runnable> unregister = new ArrayList<>();
	private final ExecutorService files = Executors.newSingleThreadExecutor(runnable ->
	{
		Thread thread = new Thread(runnable, "clue-helper-dev-timeline");
		thread.setDaemon(true);
		return thread;
	});
	private final ConfigManager configManager;
	private final Logger pluginLogger = (Logger) LoggerFactory.getLogger(PLUGIN_LOGGERS);
	private final AppenderBase<ILoggingEvent> warnings = new AppenderBase<ILoggingEvent>()
	{
		@Override
		protected void append(ILoggingEvent event)
		{
			recordWarning(event);
		}
	};
	/** The path line as last recorded; a line that only shortened along it since is the same route. */
	private List<WorldPoint> recordedLine = List.of();
	private final File sessionDir;
	private final File file;
	private Writer out;

	TimelineRecorder(Client client, StepGuide guide, ClueLink clueLink, RoutePlanner planner, WaypointOverlay waypoints,
		GuideOverlay guideOverlay, ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.client = client;
		this.guide = guide;
		this.clueLink = clueLink;
		this.planner = planner;
		this.waypoints = waypoints;
		this.guideOverlay = guideOverlay;
		this.gson = gson;
		this.guideBox = boxField();
		this.sessionDir = new File(DIR, new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
		this.file = new File(sessionDir, "timeline.jsonl");
		definePartsOfTheState();
		files.execute(this::open);
		event("settings", "values", settings());
		warnings.setContext(pluginLogger.getLoggerContext());
		warnings.start();
		pluginLogger.addAppender(warnings);
		unregister.add(clueLink.addListener((change, step) -> event("clue", "change", String.valueOf(change), "step", stepOf(step))));
		unregister.add(planner.addListener(plan -> event("plan", "plan", planOf(plan))));
		log.info("[CH-TIMELINE] recording to {}", file.getAbsolutePath());
	}

	/** The session's folder name, which other dev logs carry to point into this timeline. */
	String session()
	{
		return sessionDir.getName();
	}

	/** Where a frame of this session goes: {@code frames/<kind>/t<tick>-<name>}, so it lines up with the timeline. */
	File frameFile(String kind, String name, int tick)
	{
		return new File(sessionDir, "frames/" + kind + "/t" + tick + "-" + name);
	}

	/** A moment the tester flagged as looking wrong. Client thread. */
	void mark()
	{
		event("mark", "tile", PlayerTiles.text(PlayerTiles.tile(client)));
	}

	void stop()
	{
		pluginLogger.detachAppender(warnings);
		warnings.stop();
		unregister.forEach(Runnable::run);
		unregister.clear();
		files.execute(this::close);
		files.shutdown();
	}

	private void definePartsOfTheState()
	{
		parts.put("clue", () -> stepOf(clueLink.getCurrentStep()));
		parts.put("guide", this::currentSubStep);
		parts.put("subSteps", () -> guide.getSteps().stream().map(sub -> sub.getKind() + " " + sub.getText()).collect(Collectors.toList()));
		parts.put("box", this::shownBox);
		parts.put("highlights", guide::getHighlights);
		parts.put("outlined", this::outlined);
		parts.put("line", this::lineRoute);
		parts.put("plan", () -> planOf(planner.getPlan()));
		parts.put("committed", () -> routeOf(planner.getCommitted()));
		parts.put("player", this::player);
		parts.put("carried", this::carried);
	}

	@Subscribe(priority = -5)
	public void onGameTick(GameTick tick)
	{
		Map<String, Object> changed = new LinkedHashMap<>();
		parts.forEach((name, part) ->
		{
			Object value = safely(part);
			String json = jsonOf(value);
			if (!json.equals(lastParts.put(name, json)))
			{
				changed.put(name, value);
			}
		});
		if (!changed.isEmpty())
		{
			write("state", changed);
		}
	}

	@Subscribe(priority = -5)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (PlayerTiles.keypadOpen(client))
		{
			event("click", "on", "the bank PIN keypad");
			return;
		}
		event("click", "option", event.getMenuOption(), "target", event.getMenuTarget(), "action", String.valueOf(event.getMenuAction()),
			"id", event.getId(), "itemId", event.getItemId(), "param0", event.getParam0(), "param1", event.getParam1());
	}

	/** With the colour tags the plugin put on its lines. */
	@Subscribe(priority = -5)
	public void onMenuOpened(MenuOpened event)
	{
		if (PlayerTiles.keypadOpen(client))
		{
			return;
		}
		List<String> entries = new ArrayList<>();
		for (MenuEntry entry : event.getMenuEntries())
		{
			entries.add(entry.getOption() + " | " + entry.getTarget() + " | " + entry.getType() + " " + entry.getIdentifier());
		}
		event("menu", "entries", entries);
	}

	@Subscribe(priority = -5)
	public void onChatMessage(ChatMessage event)
	{
		if (GAME_LINES.contains(event.getType()))
		{
			event("chat", "type", String.valueOf(event.getType()), "text", event.getMessage());
		}
	}

	@Subscribe(priority = -5)
	public void onWidgetLoaded(WidgetLoaded event)
	{
		event("interface", "opened", event.getGroupId());
	}

	@Subscribe(priority = -5)
	public void onWidgetClosed(WidgetClosed event)
	{
		event("interface", "closed", event.getGroupId());
	}

	@Subscribe(priority = -5)
	public void onAnimationChanged(AnimationChanged event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			event("animation", "id", event.getActor().getAnimation());
		}
	}

	@Subscribe(priority = -5)
	public void onConfigChanged(ConfigChanged event)
	{
		if (ClueHelperConfig.GROUP.equals(event.getGroup()))
		{
			boolean profileMemory = event.getProfile() != null;
			event(profileMemory ? "memory" : "setting", "key", event.getKey(), "from", profileMemory ? size(event.getOldValue()) : event.getOldValue(),
				"to", profileMemory ? size(event.getNewValue()) : event.getNewValue(), "profile", event.getProfile());
		}
	}

	@Subscribe(priority = -5)
	public void onGameStateChanged(GameStateChanged event)
	{
		event("gameState", "state", String.valueOf(event.getGameState()));
	}

	private Map<String, Object> currentSubStep()
	{
		SubStep current = guide.getCurrent();
		Map<String, Object> sub = new LinkedHashMap<>();
		if (current == null)
		{
			return sub;
		}
		sub.put("index", guide.getSteps().indexOf(current) + 1);
		sub.put("of", guide.getSteps().size());
		sub.put("kind", current.getKind());
		sub.put("text", current.getText());
		sub.put("detail", current.getDetail());
		sub.put("waypoint", PlayerTiles.text(current.getWaypoint()));
		sub.put("goal", PlayerTiles.text(current.getGoal()));
		sub.put("radius", current.getRadius());
		sub.put("book", current.getBook());
		sub.put("row", current.getRow() == null ? null : current.getRow().getName());
		sub.put("change", current.getChange() == null ? null : current.getChange().getName());
		sub.put("option", current.getOption());
		sub.put("objectIds", current.getObjectIds());
		sub.put("npcIds", current.getNpcIds());
		sub.put("npcNames", current.getNpcNames());
		sub.put("done", current.getDone());
		sub.put("again", current.getAgain());
		sub.put("items", current.getItems());
		sub.put("actOption", current.getActOption());
		sub.put("next", guide.getNext() == null ? null : guide.getNext().getText());
		sub.put("atStep", guide.isAtStep());
		sub.put("travelling", guide.isTravelling());
		return sub;
	}

	/**
	 * The path line, recorded again only when its route changes: another end, or a tile it didn't pass before. While the
	 * player walks along it, it only loses the tiles behind them, which says nothing new.
	 */
	private List<WorldPoint> lineRoute()
	{
		List<WorldPoint> line = waypoints.getLine();
		boolean sameEnd = !line.isEmpty() && !recordedLine.isEmpty()
			&& line.get(line.size() - 1).equals(recordedLine.get(recordedLine.size() - 1));
		boolean shortenedOnly = sameEnd && new HashSet<>(recordedLine).containsAll(line);
		if (!shortenedOnly)
		{
			recordedLine = List.copyOf(line);
		}
		return recordedLine;
	}

	/** What a profile memory save is recorded as: its length, not its contents, which run to hundreds of kilobytes. */
	private static String size(String value)
	{
		return value == null ? null : value.length() + " chars";
	}

	/** Every setting of the plugin's group, by key. */
	private Map<String, String> settings()
	{
		Map<String, String> values = new LinkedHashMap<>();
		String prefix = ClueHelperConfig.GROUP + ".";
		for (String key : configManager.getConfigurationKeys(prefix))
		{
			String name = key.startsWith(prefix) ? key.substring(prefix.length()) : key;
			values.put(name, configManager.getConfiguration(ClueHelperConfig.GROUP, name));
		}
		return values;
	}

	/** Any thread. The recorder's own trouble is left out: recording it could fail the same way again. */
	private void recordWarning(ILoggingEvent event)
	{
		if (!event.getLevel().isGreaterOrEqual(Level.WARN) || event.getLoggerName().equals(TimelineRecorder.class.getName()))
		{
			return;
		}
		IThrowableProxy thrown = event.getThrowableProxy();
		event("log", "level", String.valueOf(event.getLevel()), "logger", event.getLoggerName(), "thread", event.getThreadName(),
			"message", event.getFormattedMessage(), "thrown", thrown == null ? null : thrown.getClassName() + ": " + thrown.getMessage());
	}

	/** The outlined NPCs the clue names: the overlay leaves those to core's own outline. */
	private List<String> leftToCore()
	{
		Step step = clueLink.getCurrentStep();
		if (step == null)
		{
			return List.of();
		}
		return guide.getHighlightNpcs().stream()
			.filter(npc -> npc.getName() != null && step.getNpcs().stream().anyMatch(name -> name.equalsIgnoreCase(npc.getName())))
			.map(TimelineRecorder::npcAt)
			.collect(Collectors.toList());
	}

	/** The NPCs core's Clue Scroll plugin marks itself. */
	private List<String> coreMarks()
	{
		ClueScrollPlugin core = clueLink.getCore();
		List<NPC> marked = core == null ? null : core.getNpcsToMark();
		return marked == null ? List.of() : marked.stream().map(TimelineRecorder::npcAt).collect(Collectors.toList());
	}

	/** The dev panel's mocked clue, as it describes it, or null once cleared. */
	void mocked(String description)
	{
		event("mock", "clue", description);
	}

	/** Each outlined or filled object and NPC, as id@tile. */
	private Map<String, Object> outlined()
	{
		Map<String, Object> shown = new LinkedHashMap<>();
		shown.put("objects", guide.getHighlightObjects().stream().map(TimelineRecorder::objectAt).collect(Collectors.toList()));
		shown.put("fillObjects", guide.getFillObjects().stream().map(TimelineRecorder::objectAt).collect(Collectors.toList()));
		shown.put("npcs", guide.getHighlightNpcs().stream().map(TimelineRecorder::npcAt).collect(Collectors.toList()));
		shown.put("fillNpcs", guide.getFillNpcs().stream().map(TimelineRecorder::npcAt).collect(Collectors.toList()));
		shown.put("pickUp", guide.getPickUpTiles().stream().map(PlayerTiles::text).collect(Collectors.toList()));
		shown.put("leftToCore", leftToCore());
		shown.put("coreMarks", coreMarks());
		return shown;
	}

	private Object shownBox()
	{
		if (guideBox == null)
		{
			return "unreadable";
		}
		try
		{
			return guideBox.get(guideOverlay);
		}
		catch (IllegalAccessException e)
		{
			return "unreadable: " + e.getMessage();
		}
	}

	private Map<String, Object> player()
	{
		Map<String, Object> player = new LinkedHashMap<>();
		Player local = client.getLocalPlayer();
		player.put("tile", PlayerTiles.text(PlayerTiles.tile(client)));
		player.put("animation", local == null ? null : local.getAnimation());
		player.put("interacting", local == null || local.getInteracting() == null ? null : local.getInteracting().getName());
		player.put("running", client.getVarpValue(VarPlayerID.OPTION_RUN) == 1);
		player.put("spellbook", client.getVarbitValue(VarbitID.SPELLBOOK));
		return player;
	}

	private Map<String, Object> carried()
	{
		Map<String, Object> carried = new LinkedHashMap<>();
		carried.put("inventory", items(client.getItemContainer(InventoryID.INV)));
		carried.put("worn", items(client.getItemContainer(InventoryID.WORN)));
		return carried;
	}

	private static List<String> items(ItemContainer container)
	{
		List<String> items = new ArrayList<>();
		if (container != null)
		{
			for (Item item : container.getItems())
			{
				if (item.getId() > 0)
				{
					items.add(item.getId() + "x" + item.getQuantity());
				}
			}
		}
		return items;
	}

	private static Map<String, Object> stepOf(Step step)
	{
		Map<String, Object> clue = new LinkedHashMap<>();
		if (step != null)
		{
			clue.put("key", step.getKey());
			clue.put("type", step.getType());
			clue.put("tier", step.getTier());
		}
		return clue;
	}

	private static Map<String, Object> planOf(Plan plan)
	{
		Map<String, Object> planned = new LinkedHashMap<>();
		if (plan != null)
		{
			planned.put("stepKey", plan.getStepKey());
			planned.put("best", routeOf(plan.getBest()));
			planned.put("followed", routeOf(plan.getFollowed()));
			planned.put("note", plan.getNote());
			planned.put("danger", plan.getDanger());
			planned.put("requirementNotes", plan.getRequirementNotes());
			planned.put("searchMs", plan.getSearchNanos() / 1_000_000);
		}
		return planned;
	}

	private static String routeOf(Route route)
	{
		return route == null ? null : route.getTicks() + " ticks: " + route.describe();
	}

	private static String objectAt(TileObject object)
	{
		return object.getId() + "@" + PlayerTiles.text(object.getWorldLocation());
	}

	private static String npcAt(NPC npc)
	{
		return npc.getId() + " " + npc.getName() + "@" + PlayerTiles.text(npc.getWorldLocation());
	}

	/** A part that throws is recorded as the error, so one broken getter doesn't hide the rest. */
	private static Object safely(Supplier<Object> part)
	{
		try
		{
			return part.get();
		}
		catch (RuntimeException e)
		{
			return "error: " + e;
		}
	}

	private String jsonOf(Object value)
	{
		try
		{
			return gson.toJson(value);
		}
		catch (RuntimeException e)
		{
			return gson.toJson("unwritable: " + e);
		}
	}

	/** Key-value pairs after the kind. */
	private void event(String kind, Object... pairs)
	{
		Map<String, Object> fields = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2)
		{
			fields.put(String.valueOf(pairs[i]), pairs[i + 1]);
		}
		write(kind, fields);
	}

	private void write(String kind, Map<String, Object> fields)
	{
		Map<String, Object> line = new LinkedHashMap<>();
		line.put("t", client.getTickCount());
		line.put("ms", System.currentTimeMillis());
		line.put("kind", kind);
		line.putAll(fields);
		String json = safelyJson(line);
		files.execute(() -> append(json));
	}

	private String safelyJson(Map<String, Object> line)
	{
		try
		{
			return gson.toJson(line);
		}
		catch (RuntimeException e)
		{
			return gson.toJson(Map.of("t", line.get("t"), "kind", line.get("kind"), "unwritable", String.valueOf(e)));
		}
	}

	private void open()
	{
		try
		{
			Files.createDirectories(sessionDir.toPath());
			out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			log.warn("[CH-TIMELINE] can't open {}", file.getAbsolutePath(), e);
		}
	}

	private void append(String json)
	{
		if (out == null)
		{
			return;
		}
		try
		{
			out.write(json);
			out.write('\n');
			out.flush();
		}
		catch (IOException e)
		{
			log.warn("[CH-TIMELINE] can't write {}", file.getAbsolutePath(), e);
			out = null;
		}
	}

	private void close()
	{
		if (out == null)
		{
			return;
		}
		try
		{
			out.close();
		}
		catch (IOException e)
		{
			log.warn("[CH-TIMELINE] can't close {}", file.getAbsolutePath(), e);
		}
		out = null;
	}

	/** The box GuideOverlay draws: it keeps it to itself, so the dev harness reads the field. */
	private static Field boxField()
	{
		try
		{
			Field field = GuideOverlay.class.getDeclaredField(BOX_FIELD);
			field.setAccessible(true);
			return field;
		}
		catch (NoSuchFieldException e)
		{
			log.warn("[CH-TIMELINE] GuideOverlay has no field '{}': the box won't be recorded", BOX_FIELD, e);
			return null;
		}
	}
}
