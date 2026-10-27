package com.cluehelper.route;

import java.io.IOException;
import java.util.*;
import net.runelite.api.coords.WorldPoint;

public final class NoTeleportAreas
{
	public static final NoTeleportAreas NONE = new NoTeleportAreas(List.of());
	private static final String RESOURCE = "no-teleport.tsv";
	private static final int ANY_PLANE = -1;

	private final List<int[]> boxes;

	private NoTeleportAreas(List<int[]> boxes)
	{
		this.boxes = boxes;
	}

	public static NoTeleportAreas load() throws IOException
	{
		List<int[]> boxes = new ArrayList<>();
		for (String[] c : WalkTable.lines(RESOURCE))
		{
			boxes.add(new int[]{Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3]), Integer.parseInt(c[4]),
				Integer.parseInt(c[5])});
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
