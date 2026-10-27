package com.cluehelper.route;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** Where a walk ends: on a tile, on any tile of an area, or where the game stops to use an object. */
@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WalkGoal
{
	WorldPoint southWest;
	int sizeX;
	int sizeY;
	boolean object;

	public static WalkGoal tile(WorldPoint tile)
	{
		return new WalkGoal(tile, 1, 1, false);
	}

	/** Any tile of the area will do (a Wilderness obelisk teleports whoever stands on its platform). */
	public static WalkGoal area(WorldPoint southWest, int sizeX, int sizeY)
	{
		return new WalkGoal(southWest, sizeX, sizeY, false);
	}

	/** The game walks to the first tile it finds on a straight side of the object with no wall between. */
	public static WalkGoal object(WorldPoint southWest, int sizeX, int sizeY)
	{
		return new WalkGoal(southWest, sizeX, sizeY, true);
	}

	boolean contains(int x, int y, int plane)
	{
		return plane == southWest.getPlane() && x >= southWest.getX() && x < southWest.getX() + sizeX
			&& y >= southWest.getY() && y < southWest.getY() + sizeY;
	}

	/** On a straight side of the object, outside it, with no wall between. */
	boolean isUsedFrom(Ground ground, int x, int y, int plane)
	{
		int west = southWest.getX();
		int south = southWest.getY();
		int east = west + sizeX - 1;
		int north = south + sizeY - 1;
		if (plane != southWest.getPlane())
		{
			return false;
		}
		boolean besideX = x == west - 1 || x == east + 1;
		boolean besideY = y == south - 1 || y == north + 1;
		boolean alongX = x >= west && x <= east;
		boolean alongY = y >= south && y <= north;
		if (besideX == besideY || besideX && !alongY || besideY && !alongX)
		{
			return false;
		}
		int dx = x < west ? 1 : x > east ? -1 : 0;
		int dy = y < south ? 1 : y > north ? -1 : 0;
		return !ground.isWallBetween(x, y, plane, dx, dy);
	}
}
