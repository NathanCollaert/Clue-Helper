package com.cluehelper.route;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;

/**
 * The walk table, like Shortest Path, takes doors as open: a closed one costs a click, not a detour. A locked door
 * stays shut: one with a lock option, or one the game answered with a "locked" message this session.
 */
@Singleton
public final class SceneDoors
{
	private static final String OPEN = "Open";
	private static final Set<String> LOCK_OPTIONS = Set.of("Pick-lock", "Unlock");
	private static final String LOCKED = "locked";
	private static final int NOTHING_OPENED = -1;
	private static final int WEST = 1;
	private static final int NORTH = 2;
	private static final int EAST = 4;
	private static final int SOUTH = 8;
	private static final int OBJECT_TYPE_MASK = 0x1F;
	/** A wall across a tile's diagonal (the Wizards' Tower door) is a game object blocking the whole tile. */
	private static final int DIAGONAL_WALL = 9;

	private final Map<Integer, Boolean> opensById = new HashMap<>();
	private final Set<Integer> lockedIds = new HashSet<>();
	private int lastOpened = NOTHING_OPENED;

	@Inject
	public SceneDoors()
	{
	}

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		lastOpened = OPEN.equals(event.getMenuOption()) ? event.getId() : NOTHING_OPENED;
	}

	public void onChatMessage(ChatMessage message)
	{
		boolean game = message.getType() == ChatMessageType.GAMEMESSAGE || message.getType() == ChatMessageType.SPAM;
		if (game && lastOpened != NOTHING_OPENED && message.getMessage().toLowerCase().contains(LOCKED))
		{
			lockedIds.add(lastOpened);
		}
	}

	/** Client thread only. Clears the walls of closed doors in {@code scene}, a capture of {@code view}. */
	public SceneBfs.Scene open(Client client, WorldView view, SceneBfs.Scene scene)
	{
		if (scene == null)
		{
			return null;
		}
		Tile[][][] tiles = view.getScene().getTiles();
		int[][][] flags = scene.getFlags();
		for (int plane = 0; plane < Math.min(tiles.length, flags.length); plane++)
		{
			if (flags[plane] == null)
			{
				continue;
			}
			for (int x = 0; x < tiles[plane].length; x++)
			{
				for (int y = 0; y < tiles[plane][x].length; y++)
				{
					Tile tile = tiles[plane][x][y];
					WallObject wall = tile == null ? null : tile.getWallObject();
					if (wall != null && opens(client, wall.getId()))
					{
						clearWall(flags[plane], x, y, wall.getOrientationA());
					}
					if (diagonalDoor(client, tile) != null)
					{
						clear(flags[plane], x, y, CollisionDataFlag.BLOCK_MOVEMENT_OBJECT);
					}
				}
			}
		}
		return scene;
	}

	/** Client thread only. */
	public boolean isClosed(Client client, WallObject wall)
	{
		return opens(client, wall.getId());
	}

	/** Client thread only. The closed door standing across {@code tile}'s diagonal, or null. */
	public GameObject diagonalDoor(Client client, Tile tile)
	{
		if (tile == null)
		{
			return null;
		}
		for (GameObject object : tile.getGameObjects())
		{
			if (object != null && (object.getConfig() & OBJECT_TYPE_MASK) == DIAGONAL_WALL && opens(client, object.getId()))
			{
				return object;
			}
		}
		return null;
	}

	private boolean opens(Client client, int id)
	{
		return !lockedIds.contains(id) && opensById.computeIfAbsent(id, key ->
		{
			ObjectComposition object = client.getObjectDefinition(key);
			if (object != null && object.getImpostorIds() != null)
			{
				object = object.getImpostor();
			}
			if (object == null || object.getActions() == null)
			{
				return false;
			}
			boolean open = false;
			for (String action : object.getActions())
			{
				if (action != null && LOCK_OPTIONS.contains(action))
				{
					return false;
				}
				open |= OPEN.equals(action);
			}
			return open;
		});
	}

	private static void clearWall(int[][] flags, int x, int y, int orientation)
	{
		if ((orientation & WEST) != 0)
		{
			clear(flags, x, y, CollisionDataFlag.BLOCK_MOVEMENT_WEST);
			clear(flags, x - 1, y, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
		}
		if ((orientation & NORTH) != 0)
		{
			clear(flags, x, y, CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
			clear(flags, x, y + 1, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH);
		}
		if ((orientation & EAST) != 0)
		{
			clear(flags, x, y, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
			clear(flags, x + 1, y, CollisionDataFlag.BLOCK_MOVEMENT_WEST);
		}
		if ((orientation & SOUTH) != 0)
		{
			clear(flags, x, y, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH);
			clear(flags, x, y - 1, CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
		}
	}

	private static void clear(int[][] flags, int x, int y, int flag)
	{
		if (x >= 0 && x < flags.length && y >= 0 && y < flags[x].length)
		{
			flags[x][y] &= ~flag;
		}
	}
}
