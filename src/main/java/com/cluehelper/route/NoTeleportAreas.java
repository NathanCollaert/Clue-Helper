package com.cluehelper.route;

import java.io.IOException;
import java.util.*;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;

public final class NoTeleportAreas
{
	public static final NoTeleportAreas NONE = new NoTeleportAreas(List.of());
	private static final String RESOURCE = "no-teleport.tsv";
	private static final int ANY_PLANE = -1;
	private static final int ALWAYS = -1;
	private static final int VARBIT = 5;
	private static final int LIFTED_AT = 6;

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
			boolean lifts = c.length > 7 && !c[6].isEmpty();
			boxes.add(new int[]{Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3]), Integer.parseInt(c[4]),
				Integer.parseInt(c[5]), lifts ? Integer.parseInt(c[6]) : ALWAYS, lifts ? Integer.parseInt(c[7]) : 0});
		}
		return new NoTeleportAreas(List.copyOf(boxes));
	}

	/**
	 * Client thread. The areas that block teleports now: one lifted by a varbit (swimming by the Mogre Camp until five
	 * rocks weigh the player down) is left out once it reaches its value.
	 */
	public NoTeleportAreas now(IntUnaryOperator varbitValue)
	{
		return new NoTeleportAreas(boxes.stream()
			.filter(box -> box[VARBIT] == ALWAYS || varbitValue.applyAsInt(box[VARBIT]) < box[LIFTED_AT])
			.collect(Collectors.toUnmodifiableList()));
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
