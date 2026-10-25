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

public final class SceneFiles
{
	/** Older files end after the flags; newer ones go on with the closed doors a walk may open. */
	private static final String DOORS = "doors";

	@Value
	public static class Recorded
	{
		LoadedScene scene;
		WorldPoint player;
	}

	private SceneFiles()
	{
	}

	public static void write(File file, LoadedScene scene, WorldPoint player)
	{
		try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(file)), StandardCharsets.UTF_8)))
		{
			int[][][] flags = scene.getFlags();
			out.println(scene.getBaseX() + " " + scene.getBaseY() + " " + scene.getSizeX() + " " + scene.getSizeY() + " " + flags.length
				+ " " + player.getX() + " " + player.getY() + " " + player.getPlane());
			writePlanes(out, flags);
			if (scene.getDoorSides() != null)
			{
				out.println(DOORS);
				writePlanes(out, scene.getDoorSides());
			}
		}
		catch (IOException e)
		{
			throw new IllegalStateException("scene " + file, e);
		}
	}

	private static void writePlanes(PrintWriter out, int[][][] planes)
	{
		for (int[][] plane : planes)
		{
			if (plane == null)
			{
				out.println("-");
				continue;
			}
			for (int[] column : plane)
			{
				StringBuilder line = new StringBuilder();
				for (int value : column)
				{
					line.append(line.length() == 0 ? "" : " ").append(value);
				}
				out.println(line);
			}
		}
	}

	/** A scene recorded into the test resources' scenes folder, by its file name without ".scene.gz". */
	public static Recorded resource(String name) throws IOException
	{
		try (InputStream in = SceneFiles.class.getResourceAsStream("/scenes/" + name + ".scene.gz"))
		{
			return read(in);
		}
	}

	public static Recorded read(InputStream in) throws IOException
	{
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8)))
		{
			String[] h = reader.readLine().split(" ");
			int sizeX = Integer.parseInt(h[2]);
			int planes = Integer.parseInt(h[4]);
			int[][][] flags = readPlanes(reader, planes, sizeX);
			int[][][] doorSides = DOORS.equals(reader.readLine()) ? readPlanes(reader, planes, sizeX) : null;
			LoadedScene scene = new LoadedScene(Integer.parseInt(h[0]), Integer.parseInt(h[1]), sizeX, Integer.parseInt(h[3]), flags, doorSides);
			return new Recorded(scene, new WorldPoint(Integer.parseInt(h[5]), Integer.parseInt(h[6]), Integer.parseInt(h[7])));
		}
	}

	private static int[][][] readPlanes(BufferedReader reader, int planes, int sizeX) throws IOException
	{
		int[][][] values = new int[planes][][];
		for (int p = 0; p < planes; p++)
		{
			String first = reader.readLine();
			if (first.equals("-"))
			{
				continue;
			}
			values[p] = new int[sizeX][];
			for (int x = 0; x < sizeX; x++)
			{
				String[] cells = (x == 0 ? first : reader.readLine()).split(" ");
				values[p][x] = new int[cells.length];
				for (int y = 0; y < cells.length; y++)
				{
					values[p][x][y] = Integer.parseInt(cells[y]);
				}
			}
		}
		return values;
	}
}
