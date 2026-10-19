package com.cluehelper.route;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * A live scene's collision flags as a gzipped text file, recorded by the dev plugin for the scene BFS check:
 * a header line (base x, base y, size x, size y, planes, player x, y, plane), then per plane one line per x
 * of size-y flags, or "-" for a plane with no data.
 */
public final class SceneFiles
{
	@Value
	static class Recorded
	{
		SceneBfs.Scene scene;
		WorldPoint player;
	}

	private SceneFiles()
	{
	}

	public static void write(File file, SceneBfs.Scene scene, WorldPoint player)
	{
		try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(file)), StandardCharsets.UTF_8)))
		{
			int[][][] flags = scene.getFlags();
			out.println(scene.getBaseX() + " " + scene.getBaseY() + " " + scene.getSizeX() + " " + scene.getSizeY() + " " + flags.length
				+ " " + player.getX() + " " + player.getY() + " " + player.getPlane());
			for (int[][] plane : flags)
			{
				if (plane == null)
				{
					out.println("-");
					continue;
				}
				for (int[] column : plane)
				{
					StringBuilder line = new StringBuilder();
					for (int flag : column)
					{
						line.append(line.length() == 0 ? "" : " ").append(flag);
					}
					out.println(line);
				}
			}
		}
		catch (IOException e)
		{
			throw new IllegalStateException("scene " + file, e);
		}
	}

	static Recorded read(InputStream in) throws IOException
	{
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8)))
		{
			String[] h = reader.readLine().split(" ");
			int sizeX = Integer.parseInt(h[2]);
			int planes = Integer.parseInt(h[4]);
			int[][][] flags = new int[planes][][];
			for (int p = 0; p < planes; p++)
			{
				String first = reader.readLine();
				if (first.equals("-"))
				{
					continue;
				}
				flags[p] = new int[sizeX][];
				for (int x = 0; x < sizeX; x++)
				{
					String[] cells = (x == 0 ? first : reader.readLine()).split(" ");
					flags[p][x] = new int[cells.length];
					for (int y = 0; y < cells.length; y++)
					{
						flags[p][x][y] = Integer.parseInt(cells[y]);
					}
				}
			}
			SceneBfs.Scene scene = new SceneBfs.Scene(Integer.parseInt(h[0]), Integer.parseInt(h[1]), sizeX, Integer.parseInt(h[3]), flags);
			return new Recorded(scene, new WorldPoint(Integer.parseInt(h[5]), Integer.parseInt(h[6]), Integer.parseInt(h[7])));
		}
	}
}
