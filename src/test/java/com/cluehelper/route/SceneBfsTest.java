package com.cluehelper.route;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

/**
 * The recorded collision flags and Shortest Path's collision map are separate data, so a wrong movement rule or a
 * stale map shows here.
 */
public class SceneBfsTest
{
	private static final List<String> SCENES = List.of("2880_3232_0", "2888_3232_0");
	/** A door or gate closed in the recorded scene but open in Shortest Path's map costs a few tiles of detour. */
	private static final int TOLERANCE_TILES = 4;

	@Test
	public void sceneWalksMatchTheTable() throws IOException
	{
		WalkTable table = WalkTable.load();
		boolean[] allRows = new boolean[table.rowCount()];
		Arrays.fill(allRows, true);
		List<String> mismatches = new ArrayList<>();
		int compared = 0;
		int exact = 0;
		for (String name : SCENES)
		{
			SceneFiles.Recorded recorded = read(name);
			SceneBfs.Scene scene = recorded.getScene();
			for (int from = 0; from < table.nodeCount(); from++)
			{
				WorldPoint start = table.placed(from);
				if (start == null || !scene.contains(start))
				{
					continue;
				}
				SceneBfs.Result bfs = SceneBfs.run(scene, start, table, allRows);
				for (int e = table.entriesFrom(from); e < table.entriesEnd(from); e++)
				{
					WorldPoint end = table.placed(table.entryTo(e));
					int walked = bfs.cost(end);
					int expected = table.entryFull(e);
					if (end == null || !scene.contains(end) || walked > expected + TOLERANCE_TILES && !insideScene(bfs, expected))
					{
						continue;
					}
					compared++;
					exact += walked == expected ? 1 : 0;
					if (Math.abs(walked - expected) > TOLERANCE_TILES)
					{
						mismatches.add(name + ": node " + from + " " + start + " -> node " + table.entryTo(e) + " " + end + " table "
							+ expected + " scene " + (walked == SceneBfs.UNREACHED ? "unreached" : walked));
					}
				}
			}
		}
		System.out.printf("scene BFS against the table: %d in-scene pairs, %d exact, %d beyond %d tiles%n", compared, exact,
			mismatches.size(), TOLERANCE_TILES);
		assertTrue(compared > 0);
		assertTrue(String.join("\n", mismatches), mismatches.isEmpty());
	}

	/**
	 * Past the walk's own waypoint, with the goal up stairs beyond the scene's edge, the line still leads on: Daer Krand
	 * in the Sisterhood Sanctuary, from the corridor north of the medallion's landing.
	 */
	@Test
	public void lineLeadsOnPastTheWaypoint() throws IOException
	{
		WalkTable table = WalkTable.load();
		boolean[] allRows = new boolean[table.rowCount()];
		Arrays.fill(allRows, true);
		SceneBfs.Scene scene = read("3760_9648_1").getScene();
		for (WorldPoint at : List.of(new WorldPoint(3814, 9731, 1), new WorldPoint(3826, 9733, 1)))
		{
			List<WorldPoint> line = ScenePath.toward(SceneBfs.run(scene, at, table, allRows), table, new WorldPoint(3821, 9780, 2));
			assertTrue("a line from " + at, line.size() > 1);
		}
	}

	/** A table walk longer than the scene is wide may leave the scene, where the BFS can't follow. */
	private static boolean insideScene(SceneBfs.Result bfs, int expected)
	{
		return expected <= Math.min(bfs.getScene().getSizeX(), bfs.getScene().getSizeY()) / 2;
	}

	private static SceneFiles.Recorded read(String name) throws IOException
	{
		try (InputStream in = SceneBfsTest.class.getResourceAsStream("/scenes/" + name + ".scene.gz"))
		{
			return SceneFiles.read(in);
		}
	}
}
