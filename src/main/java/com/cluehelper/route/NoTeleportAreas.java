package com.cluehelper.route;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** The bundled {@code no-teleport.tsv}: boxes where no teleport works (origin class {@link OriginClass#NO_TELEPORT}). */
public final class NoTeleportAreas
{
	public static final NoTeleportAreas NONE = new NoTeleportAreas(List.of());
	static final String RESOURCE = "no-teleport.tsv";
	private static final int ANY_PLANE = -1;

	private final List<int[]> boxes;

	private NoTeleportAreas(List<int[]> boxes)
	{
		this.boxes = boxes;
	}

	public static NoTeleportAreas load() throws IOException
	{
		List<int[]> boxes = new ArrayList<>();
		try (InputStream in = NoTeleportAreas.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				throw new IOException("missing resource " + RESOURCE);
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			boolean header = true;
			String line;
			while ((line = reader.readLine()) != null)
			{
				if (line.isEmpty() || line.startsWith("#"))
				{
					continue;
				}
				if (header)
				{
					header = false;
					continue;
				}
				String[] c = line.split("\t");
				boxes.add(new int[]{Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3]), Integer.parseInt(c[4]),
					Integer.parseInt(c[5])});
			}
		}
		return new NoTeleportAreas(List.copyOf(boxes));
	}

	public boolean contains(WorldPoint tile)
	{
		for (int[] box : boxes)
		{
			if (tile.getX() >= box[0] && tile.getY() >= box[1] && tile.getX() <= box[2] && tile.getY() <= box[3]
				&& (box[4] == ANY_PLANE || box[4] == tile.getPlane()))
			{
				return true;
			}
		}
		return false;
	}
}
