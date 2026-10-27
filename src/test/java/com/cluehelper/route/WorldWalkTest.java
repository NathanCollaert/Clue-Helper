package com.cluehelper.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class WorldWalkTest
{
	/**
	 * A wall along x = 15 with a closed door at y = 16 and a gap at y = 19: one tile further round beats opening the
	 * door. The client blocks the scene's edge rows, so the wall stands well inside.
	 */
	@Test
	public void aShortDetourBeatsOpeningADoor()
	{
		int size = 32;
		int wallX = 15;
		int doorY = 16;
		int gapY = 19;
		int[][] flags = new int[size][size];
		int[][] doorSides = new int[size][size];
		for (int y = 0; y < gapY; y++)
		{
			int[][] sides = y == doorY ? doorSides : flags;
			sides[wallX][y] |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;
			sides[wallX - 1][y] |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		}
		LoadedScene scene = new LoadedScene(0, 0, size, size, new int[][][] {flags}, new int[][][] {doorSides});
		WorldPoint start = new WorldPoint(wallX - 3, doorY, 0);
		List<WorldPoint> line = new WorldWalk().path(Ground.sceneOnly(scene), start, WalkGoal.tile(new WorldPoint(wallX + 3, doorY, 0)));

		assertEquals(start, line.get(0));
		assertFalse("through the door: " + line, line.contains(new WorldPoint(wallX, doorY, 0)));
	}

	/** Walking to a chest ends where it is used, on a straight side with no wall between: never against its walled side. */
	@Test
	public void anObjectIsWalkedToWhereItIsUsed()
	{
		int size = 32;
		WorldPoint chest = new WorldPoint(15, 15, 0);
		int[][] flags = new int[size][size];
		flags[chest.getX()][chest.getY()] |= CollisionDataFlag.BLOCK_MOVEMENT_FULL;
		flags[chest.getX()][chest.getY()] |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		flags[chest.getX() - 1][chest.getY()] |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		LoadedScene scene = new LoadedScene(0, 0, size, size, new int[][][] {flags});
		List<WorldPoint> line = new WorldWalk().path(Ground.sceneOnly(scene), new WorldPoint(10, 15, 0), WalkGoal.object(chest, 1, 1));

		WorldPoint end = line.get(line.size() - 1);
		assertEquals("ends beside the chest: " + line, 1, Math.abs(end.getX() - chest.getX()) + Math.abs(end.getY() - chest.getY()));
		assertFalse("against the wall: " + line, end.equals(chest.dx(-1)));
	}

	/**
	 * A goal in a house's corner, walked to from outside that corner: the line goes round through the doorway on the far
	 * side, never ends against the wall beside the goal.
	 */
	@Test
	public void aGoalInsideAHouseIsReachedThroughItsDoorway()
	{
		int size = 32;
		int low = 11;
		int high = 19;
		int doorwayX = 15;
		int[][] flags = new int[size][size];
		for (int i = low; i <= high; i++)
		{
			wall(flags, low, i, CollisionDataFlag.BLOCK_MOVEMENT_WEST, -1, 0, CollisionDataFlag.BLOCK_MOVEMENT_EAST);
			wall(flags, high, i, CollisionDataFlag.BLOCK_MOVEMENT_EAST, 1, 0, CollisionDataFlag.BLOCK_MOVEMENT_WEST);
			wall(flags, i, low, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH, 0, -1, CollisionDataFlag.BLOCK_MOVEMENT_NORTH);
			if (i != doorwayX)
			{
				wall(flags, i, high, CollisionDataFlag.BLOCK_MOVEMENT_NORTH, 0, 1, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH);
			}
		}
		LoadedScene scene = new LoadedScene(0, 0, size, size, new int[][][] {flags});
		WorldPoint corner = new WorldPoint(low, low, 0);
		List<WorldPoint> line = new WorldWalk().path(Ground.sceneOnly(scene), new WorldPoint(low - 2, low + 1, 0), WalkGoal.tile(corner));

		assertEquals("ends in the corner: " + line, corner, line.get(line.size() - 1));
	}

	private static void wall(int[][] flags, int x, int y, int side, int dx, int dy, int facingSide)
	{
		flags[x][y] |= side;
		flags[x + dx][y + dy] |= facingSide;
	}

	/**
	 * Past the loaded scene's edge, up stairs, the walk still reaches the goal, every step one the game walks: Daer
	 * Krand in the Sisterhood Sanctuary, from the corridor north of the medallion's landing, a long walk across
	 * Lumbridge to Draynor's bank with no scene at all, and Doris' trapdoor in Edgeville, through her door rather
	 * than the wall beside it.
	 */
	@Test
	public void walksPastTheSceneTileByTile() throws IOException
	{
		WalkTable table = WalkTable.load();
		WorldMap map = WorldMap.load();
		LoadedScene sanctuary = SceneFiles.resource("3760_9648_1").getScene();
		WorldWalk walk = new WorldWalk();
		WorldPoint daerKrand = new WorldPoint(3821, 9780, 2);
		for (WorldPoint at : List.of(new WorldPoint(3814, 9731, 1), new WorldPoint(3826, 9733, 1)))
		{
			assertWalks(walk, Ground.world(map, sanctuary, table, row -> true), at, daerKrand);
		}
		assertWalks(walk, Ground.world(map, null, table, row -> true), new WorldPoint(3222, 3218, 0), new WorldPoint(3092, 3245, 0));
		LoadedScene edgeville = SceneFiles.resource("3048_3424_0").getScene();
		assertWalks(walk, Ground.world(map, edgeville, table, row -> true), new WorldPoint(3080, 3501, 0), new WorldPoint(3077, 3493, 0));
	}

	private static void assertWalks(WorldWalk walk, Ground ground, WorldPoint from, WorldPoint to)
	{
		long started = System.nanoTime();
		List<WorldPoint> line = walk.path(ground, from, WalkGoal.tile(to));
		System.out.printf("%s -> %s: %d tiles in %.1f ms%n", from, to, line.size(), (System.nanoTime() - started) / 1e6);
		assertEquals(from, line.get(0));
		WorldPoint end = line.get(line.size() - 1);
		assertTrue("ends at " + end, end.getPlane() == to.getPlane() && end.distanceTo2D(to) <= 2);
		for (int i = 0; i + 1 < line.size(); i++)
		{
			WorldPoint a = line.get(i);
			WorldPoint b = line.get(i + 1);
			boolean onFoot = a.getPlane() == b.getPlane() && a.distanceTo2D(b) == 1;
			assertTrue("step " + a + " -> " + b, !onFoot || canStep(ground, a, b));
		}
	}

	/** A walk, or a straight step with no wall between onto a blocked tile a row starts from. */
	private static boolean canStep(Ground ground, WorldPoint a, WorldPoint b)
	{
		for (int d = 0; d < Directions.COUNT; d++)
		{
			if (a.getX() + Directions.DX[d] == b.getX() && a.getY() + Directions.DY[d] == b.getY())
			{
				return ground.canMove(a.getX(), a.getY(), a.getPlane(), d)
					|| d < Directions.STRAIGHT && ground.openRowsFrom(WalkTable.pack(b)).length > 0
					&& !ground.isWallBetween(a.getX(), a.getY(), a.getPlane(), Directions.DX[d], Directions.DY[d]);
			}
		}
		return false;
	}
}
