package com.cluehelper.route;

import com.cluehelper.player.PlayerSnapshot;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.inject.*;
import lombok.Value;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;

/**
 * The walk table, like Shortest Path, takes doors as open: a closed one costs a click, not a detour. A locked door
 * stays shut: one with a lock option, or one the game answered with a "locked" message this session.
 */
@Singleton
public final class SceneDoors
{
	private static final String OPEN = "Open";
	/** A web across a way (the Wilderness lever's room) is slashed through like a door is opened. */
	private static final String SLASH = "Slash";
	/** A fence or railing squeezed through (King Lathas' camp) is passed like an opened door. */
	private static final String SQUEEZE = "Squeeze-through";
	/** A doorway walked through (the Lighthouse's) is passed like an opened door. */
	private static final String WALK_THROUGH = "Walk-through";
	private static final Set<String> LOCK_OPTIONS = Set.of("Pick-lock", "Unlock");
	private static final String LOCKED = "locked";
	private static final int WEST = 1;
	private static final int NORTH = 2;
	private static final int EAST = 4;
	private static final int SOUTH = 8;
	private static final int OBJECT_TYPE_MASK = 0x1F;
	/** A wall across a tile's diagonal (the Wizards' Tower door) is a game object blocking the whole tile. */
	private static final int DIAGONAL_WALL = 9;
	private static final int ALL_SIDES = CollisionDataFlag.BLOCK_MOVEMENT_WEST | CollisionDataFlag.BLOCK_MOVEMENT_NORTH
		| CollisionDataFlag.BLOCK_MOVEMENT_EAST | CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;

	private static final String NONE = "";
	/** Shortest Path's SLASH items (knives, axes, blades), as the catalog generator writes them. */
	private static final Set<Integer> SLASH_ITEMS = slashItems();

	/** The option that opens each object: Open, Slash, Squeeze-through, Walk-through, or NONE. */
	private final Map<Integer, String> openOptions = new HashMap<>();
	/** By place: doors share ids across the game, and one locked door doesn't lock every other one like it. */
	private final Set<Door> locked = new HashSet<>();
	private Door lastOpened;
	private volatile boolean canSlash;
	@Inject
	private Client client;

	/** From each new snapshot: a web opens only to a player carrying or wielding something that slashes. */
	public void playerChanged(PlayerSnapshot player)
	{
		canSlash = player.carriedIds().stream().anyMatch(SLASH_ITEMS::contains);
	}

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		WorldView view = client.getTopLevelWorldView();
		boolean open = OPEN.equals(event.getMenuOption()) && view != null;
		lastOpened = open ? new Door(event.getId(), WorldPoint.fromScene(view, event.getParam0(), event.getParam1(), view.getPlane())) : null;
	}

	public void onChatMessage(ChatMessage message)
	{
		boolean game = message.getType() == ChatMessageType.GAMEMESSAGE || message.getType() == ChatMessageType.SPAM;
		if (game && lastOpened != null && message.getMessage().toLowerCase().contains(LOCKED))
		{
			locked.add(lastOpened);
		}
	}

	/**
	 * Client thread only. A copy of {@code scene}, a capture of {@code view}, with the walls of closed doors cleared and
	 * the sides they open through recorded. A door that {@code table} crosses by a row (one that shuts behind the
	 * player, its opening animated) stays shut, so its row's time counts.
	 */
	public LoadedScene withDoorsOpen(WorldView view, LoadedScene scene, WalkTable table)
	{
		if (scene == null)
		{
			return null;
		}
		Tile[][][] tiles = view.getScene().getTiles();
		int[][][] flags = LoadedScene.copy(scene.getFlags());
		int[][][] doorSides = new int[flags.length][][];
		for (int plane = 0; plane < Math.min(tiles.length, flags.length); plane++)
		{
			if (flags[plane] == null)
			{
				continue;
			}
			doorSides[plane] = new int[flags[plane].length][flags[plane][0].length];
			for (int x = 0; x < tiles[plane].length; x++)
			{
				for (int y = 0; y < tiles[plane][x].length; y++)
				{
					Tile tile = tiles[plane][x][y];
					WallObject wall = tile == null ? null : tile.getWallObject();
					if (wall != null && opensOnClick(wall) && !passedByRow(table, wall.getId(), view, x, y, plane))
					{
						clearWall(flags[plane], doorSides[plane], x, y, wall.getOrientationA());
					}
					if (diagonalDoor(tile) != null)
					{
						clear(flags[plane], x, y, CollisionDataFlag.BLOCK_MOVEMENT_OBJECT);
						doorSides[plane][x][y] |= ALL_SIDES;
					}
				}
			}
		}
		return new LoadedScene(scene.getBaseX(), scene.getBaseY(), scene.getSizeX(), scene.getSizeY(), flags, doorSides);
	}

	/** Client thread only. */
	/** A door, web, fence or doorway the path can go through with one click. */
	public boolean opensOnClick(WallObject wall)
	{
		return opens(wall.getId()) && !locked.contains(new Door(wall.getId(), wall.getWorldLocation()));
	}

	/** Client thread only. The closed door standing across {@code tile}'s diagonal, or null. */
	public GameObject diagonalDoor(Tile tile)
	{
		if (tile == null)
		{
			return null;
		}
		for (GameObject object : tile.getGameObjects())
		{
			if (object != null && (object.getConfig() & OBJECT_TYPE_MASK) == DIAGONAL_WALL && opens(object.getId())
				&& !locked.contains(new Door(object.getId(), object.getWorldLocation())))
			{
				return object;
			}
		}
		return null;
	}

	private static boolean passedByRow(WalkTable table, int id, WorldView view, int sceneX, int sceneY, int plane)
	{
		return table != null && table.isRowObjectAt(id, new WorldPoint(view.getBaseX() + sceneX, view.getBaseY() + sceneY, plane));
	}

	private boolean opens(int id)
	{
		String option = openOption(id);
		return (OPEN.equals(option) || SQUEEZE.equalsIgnoreCase(option) || WALK_THROUGH.equalsIgnoreCase(option)
			|| SLASH.equals(option) && canSlash);
	}

	/** Client thread only. The option that opens a door, web, fence to squeeze through or doorway, or "" for anything else. */
	public String openOption(int id)
	{
		return openOptions.computeIfAbsent(id, key ->
		{
			ObjectComposition object = client.getObjectDefinition(key);
			if (object != null && object.getImpostorIds() != null)
			{
				object = object.getImpostor();
			}
			String option = NONE;
			for (String action : object == null || object.getActions() == null ? new String[0] : object.getActions())
			{
				if (action != null && LOCK_OPTIONS.contains(action))
				{
					return NONE;
				}
				option = OPEN.equals(action) || SLASH.equals(action) || SQUEEZE.equalsIgnoreCase(action) || WALK_THROUGH.equalsIgnoreCase(action)
					? action : option;
			}
			return option;
		});
	}

	private static Set<Integer> slashItems()
	{
		try (InputStream in = SceneDoors.class.getResourceAsStream("slash.txt"))
		{
			if (in == null)
			{
				throw new IllegalStateException("slash.txt is missing");
			}
			Set<Integer> ids = new HashSet<>();
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R"))
			{
				if (!line.isBlank() && !line.startsWith("#"))
				{
					ids.add(Integer.parseInt(line.trim()));
				}
			}
			return Set.copyOf(ids);
		}
		catch (IOException e)
		{
			throw new IllegalStateException("can't read slash.txt", e);
		}
	}

	private static void clearWall(int[][] flags, int[][] doorSides, int x, int y, int orientation)
	{
		if ((orientation & WEST) != 0)
		{
			open(flags, doorSides, x, y, CollisionDataFlag.BLOCK_MOVEMENT_WEST);
			open(flags, doorSides, x - 1, y, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		}
		if ((orientation & NORTH) != 0)
		{
			open(flags, doorSides, x, y, CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
			open(flags, doorSides, x, y + 1, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH);
		}
		if ((orientation & EAST) != 0)
		{
			open(flags, doorSides, x, y, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
			open(flags, doorSides, x + 1, y, CollisionDataFlag.BLOCK_MOVEMENT_WEST);
		}
		if ((orientation & SOUTH) != 0)
		{
			open(flags, doorSides, x, y, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH);
			open(flags, doorSides, x, y - 1, CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
		}
	}

	private static void open(int[][] flags, int[][] doorSides, int x, int y, int side)
	{
		clear(flags, x, y, side);
		if (x >= 0 && x < doorSides.length && y >= 0 && y < doorSides[x].length)
		{
			doorSides[x][y] |= side;
		}
	}

	private static void clear(int[][] flags, int x, int y, int flag)
	{
		if (x >= 0 && x < flags.length && y >= 0 && y < flags[x].length)
		{
			flags[x][y] &= ~flag;
		}
	}

	@Value
	private static class Door
	{
		int id;
		WorldPoint at;
	}
}
