package com.cluehelper;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Times every door, gate, agility shortcut, stair and ladder the player crosses, against what the walk table priced
 * it at: one {@link Crossed} line each in {@link #LOG}, summed up per walk row in {@link #SUMMARY}.
 *
 * <p>A walk row (Clue-Helper-Tools' {@code w0_rows.tsv}) is timed from the player standing on its origin to standing on
 * its destination, plus the arrival lock ({@link LockWatch}); the bake prices it at the larger of 2 x its Duration
 * and 1 half-tick, or 4 when it changes floor or jumps over {@link #FAR_ROW_TILES} tiles. A closed door that is no row
 * is priced as open, so 0; it is timed from the player standing at it to the door opening, plus the lock of the walk
 * click through it.
 *
 * <p>Ticks: the player is first seen on a tile on GameTick with count S, so stands there from boundary S + 1, or from
 * the click's own period when already there; an arrival or a door opening seen with count E happened at boundary E + 1.
 * The waypoint line's crossing windows are sampled into {@link WaitWindows} as it counts them: from the click to the
 * player first moving or animating, and to standing across.
 */
@Slf4j
final class CrossingTrace
{
	static final String TAG = "[CH-CROSS]";
	static final File LOG = new File(TimingLog.DIR, "crossings.jsonl");
	static final File SUMMARY = new File(TimingLog.DIR, "crossings.tsv");
	private static final File WALK_ROWS = new File("Clue-Helper-Tools/tools/walk/out/w0_rows.tsv");
	/** The options SceneDoors passes a closed door with. */
	private static final Set<String> DOOR_OPTIONS = Set.of("Open", "Slash", "Squeeze-through", "Walk-through");
	private static final Set<MenuAction> OBJECT_OPTIONS = Set.of(MenuAction.GAME_OBJECT_FIRST_OPTION,
		MenuAction.GAME_OBJECT_SECOND_OPTION, MenuAction.GAME_OBJECT_THIRD_OPTION, MenuAction.GAME_OBJECT_FOURTH_OPTION,
		MenuAction.GAME_OBJECT_FIFTH_OPTION);
	private static final int FAR_ROW_TILES = 40;
	private static final int FLOOR_OR_FAR_HALF_TICKS = 4;
	private static final int GIVE_UP_TICKS = 50;
	private static final int LOCK_WATCH_TICKS = 15;

	private enum Phase
	{
		IDLE,
		/** Clicked; walking to it or crossing it. */
		CROSSING,
		/** Across, or the door open; waiting for a walk click that moves the player. */
		ACROSS
	}

	private final Client client;
	private final Gson gson;
	private final TimingLog timingLog;
	private final WaitWindows windows;
	private final Map<Integer, List<WalkRow>> rowsByObject;
	private final LockWatch lock = new LockWatch();
	private Phase phase = Phase.IDLE;
	private Crossed crossed;
	private List<WalkRow> candidates = List.of();
	private int clickedSceneX;
	private int clickedSceneY;
	private WorldPoint tileAtClick;
	private final Map<WorldPoint, Integer> firstSeen = new HashMap<>();

	CrossingTrace(Client client, Gson gson, TimingLog timingLog, WaitWindows windows)
	{
		this.client = client;
		this.gson = gson;
		this.timingLog = timingLog;
		this.windows = windows;
		this.rowsByObject = WalkRow.load(WALK_ROWS);
	}

	@Subscribe(priority = -3)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (PlayerTiles.keypadOpen(client))
		{
			return;
		}
		String option = Text.removeTags(event.getMenuOption());
		if (phase == Phase.ACROSS && option.equals(PlayerTiles.WALK))
		{
			lock.onWalkClick(client.getTickCount());
			return;
		}
		if (phase == Phase.ACROSS)
		{
			finish(lock.settle(PlayerTiles.tile(client)));
		}
		phase = Phase.IDLE;
		if (OBJECT_OPTIONS.contains(event.getMenuAction()))
		{
			beginIfCrossing(event, option, Text.removeTags(event.getMenuTarget()));
		}
	}

	@Subscribe(priority = -3)
	public void onAnimationChanged(AnimationChanged event)
	{
		if (phase != Phase.IDLE && event.getActor() == client.getLocalPlayer() && event.getActor().getAnimation() != -1)
		{
			crossed.animations.add("t" + client.getTickCount() + " " + event.getActor().getAnimation());
		}
	}

	@Subscribe(priority = -3)
	public void onWallObjectDespawned(WallObjectDespawned event)
	{
		onObjectGone(event.getWallObject());
	}

	@Subscribe(priority = -3)
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		onObjectGone(event.getGameObject());
	}

	@Subscribe(priority = -3)
	public void onGameTick(GameTick tick)
	{
		lock.onGameTick();
		WorldPoint tile = PlayerTiles.tile(client);
		int now = client.getTickCount();
		if (phase == Phase.CROSSING)
		{
			firstSeen.putIfAbsent(tile, now);
			noteActive(tile, now);
			WalkRow done = candidates.stream().filter(row -> row.dest.equals(tile) && wasAt(row.origin)).findFirst().orElse(null);
			if (done != null)
			{
				across(done.origin, done);
			}
			else if (now - crossed.clickTick > GIVE_UP_TICKS)
			{
				crossed.outcome = Crossed.Outcome.NOT_ACROSS;
				crossed.endedAt = PlayerTiles.text(tile);
				finish(LockWatch.Lock.NONE);
			}
		}
		else if (phase == Phase.ACROSS && (lock.hasMoved(tile) || now - crossed.acrossTick > LOCK_WATCH_TICKS))
		{
			finish(lock.settle(tile));
		}
	}

	/** As the waypoint line sees a crossing under way: the player has stepped or animates. */
	private void noteActive(WorldPoint tile, int now)
	{
		Player local = client.getLocalPlayer();
		boolean animating = local != null && local.getAnimation() != -1;
		if (crossed.activeTick == 0 && (animating || tile != null && !tile.equals(tileAtClick)))
		{
			crossed.activeTick = now;
		}
	}

	private void beginIfCrossing(MenuOptionClicked event, String option, String target)
	{
		List<WalkRow> rows = rowsByObject.getOrDefault(event.getId(), List.of()).stream()
			.filter(row -> row.label.startsWith(option + " "))
			.collect(Collectors.toList());
		boolean closedDoor = rows.isEmpty() && DOOR_OPTIONS.contains(option) && isWallObject(event.getParam0(), event.getParam1(), event.getId());
		if (rows.isEmpty() && !closedDoor)
		{
			return;
		}
		candidates = rows;
		clickedSceneX = event.getParam0();
		clickedSceneY = event.getParam1();
		tileAtClick = PlayerTiles.tile(client);
		firstSeen.clear();
		crossed = new Crossed();
		crossed.click = option + " " + target;
		crossed.objectId = event.getId();
		crossed.clickTick = client.getTickCount();
		crossed.walkRow = !rows.isEmpty();
		phase = Phase.CROSSING;
	}

	private void onObjectGone(TileObject object)
	{
		if (phase == Phase.CROSSING && !crossed.walkRow && object.getId() == crossed.objectId
			&& object.getLocalLocation().getSceneX() == clickedSceneX && object.getLocalLocation().getSceneY() == clickedSceneY)
		{
			across(PlayerTiles.tile(client), null);
		}
	}

	private void across(WorldPoint start, WalkRow row)
	{
		int now = client.getTickCount();
		WorldPoint tile = PlayerTiles.tile(client);
		crossed.outcome = Crossed.Outcome.ACROSS;
		crossed.acrossTick = now;
		crossed.startTick = start.equals(tileAtClick) ? crossed.clickTick : firstSeen.getOrDefault(start, now) + 1;
		crossed.crossTicks = now + 1 - crossed.startTick;
		crossed.origin = PlayerTiles.text(start);
		crossed.endedAt = PlayerTiles.text(tile);
		if (row != null)
		{
			crossed.label = row.label;
			crossed.kind = row.kind;
			crossed.source = row.source;
			crossed.duration = row.duration;
			crossed.plannedHalfTicks = row.pricedHalfTicks();
		}
		lock.arrived(now, tile);
		phase = Phase.ACROSS;
		String detail = crossed.walkRow ? crossed.label : crossed.click;
		if (crossed.activeTick != 0)
		{
			windows.sample(WaitWindows.Window.CROSSING_START, crossed.activeTick - crossed.clickTick, detail);
		}
		windows.sample(WaitWindows.Window.CROSSING, now - crossed.clickTick, detail);
	}

	private boolean wasAt(WorldPoint tile)
	{
		return tile.equals(tileAtClick) || firstSeen.containsKey(tile);
	}

	private boolean isWallObject(int sceneX, int sceneY, int objectId)
	{
		WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return false;
		}
		Tile[][] plane = view.getScene().getTiles()[view.getPlane()];
		Tile tile = sceneX >= 0 && sceneX < plane.length && sceneY >= 0 && sceneY < plane[sceneX].length ? plane[sceneX][sceneY] : null;
		WallObject wall = tile == null ? null : tile.getWallObject();
		return wall != null && wall.getId() == objectId;
	}

	private void finish(LockWatch.Lock settled)
	{
		crossed.lockAtLeast = settled.atLeast;
		crossed.lockAtMost = settled.atMost;
		String line = gson.toJson(crossed);
		log.info("{} {}", TAG, line);
		timingLog.append(LOG, line, SUMMARY, lines -> CrossingTimings.tsv(lines.stream()
			.map(logged -> gson.fromJson(logged, Crossed.class))
			.collect(Collectors.toList())));
		crossed = null;
		phase = Phase.IDLE;
	}

	/** A row of {@code w0_rows.tsv}: Shortest Path's doors, stairs and shortcuts as the walk table was baked from. */
	private static final class WalkRow
	{
		private final WorldPoint origin;
		private final WorldPoint dest;
		private final int duration;
		private final String kind;
		/** The Shortest Path file and line it came from. */
		private final String source;
		/** Option, object name and object id, as in "Open Door 9398". */
		private final String label;

		private WalkRow(String[] cells)
		{
			origin = new WorldPoint(Integer.parseInt(cells[0]), Integer.parseInt(cells[1]), Integer.parseInt(cells[2]));
			dest = new WorldPoint(Integer.parseInt(cells[3]), Integer.parseInt(cells[4]), Integer.parseInt(cells[5]));
			duration = Integer.parseInt(cells[6]);
			kind = cells[8];
			source = cells[9];
			label = cells[10];
		}

		/** As WalkGen prices a row: walk cost is in tiles, a tile per half-tick. */
		private int pricedHalfTicks()
		{
			boolean floorOrFar = origin.getPlane() != dest.getPlane() || origin.distanceTo2D(dest) > FAR_ROW_TILES;
			return Math.max(floorOrFar ? FLOOR_OR_FAR_HALF_TICKS : 1, 2 * duration);
		}

		/** By the object id that ends the label; rows whose label has none can't be told apart and are left out. */
		private static Map<Integer, List<WalkRow>> load(File file)
		{
			Map<Integer, List<WalkRow>> byObject = new HashMap<>();
			try
			{
				List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
				for (String line : lines.subList(1, lines.size()))
				{
					WalkRow row = new WalkRow(line.split("\t", -1));
					String id = row.label.substring(row.label.lastIndexOf(' ') + 1);
					if (id.matches("\\d+"))
					{
						byObject.computeIfAbsent(Integer.parseInt(id), k -> new ArrayList<>()).add(row);
					}
				}
			}
			catch (IOException e)
			{
				log.warn("{} no walk rows at {}: crossings are timed without their priced cost", TAG, file.getAbsolutePath(), e);
			}
			return byObject;
		}
	}

	/** One line of the log. The row fields stay empty for a closed door that is no walk row. */
	static final class Crossed
	{
		enum Outcome
		{
			ACROSS,
			/** Never reached a row's destination from its origin: cancelled, refused, or the row's tiles are off. */
			NOT_ACROSS
		}

		/** Option and target, as the menu showed them. */
		String click;
		int objectId;
		boolean walkRow;
		String label;
		String kind;
		String source;
		int duration;
		int plannedHalfTicks;
		Outcome outcome;
		int clickTick;
		/** The boundary from which the player stood on the origin, or at the door. */
		int startTick;
		int acrossTick;
		int crossTicks;
		/** The first game tick the player had stepped or animated since the click; 0 when never. */
		int activeTick;
		int lockAtLeast;
		/** {@link LockWatch#UNKNOWN} when no accepted walk click told it. */
		int lockAtMost = LockWatch.UNKNOWN;
		String origin;
		String endedAt;
		List<String> animations = new ArrayList<>();
	}
}
