package com.cluehelper.route;

import lombok.*;
import net.runelite.api.coords.WorldPoint;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PlanStart
{
	public enum Kind
	{
		SCENE,
		AT_NODE,
		TELEPORT_ONLY
	}

	Kind kind;
	WorldPoint tile;
	/** The walk from the tile; also kept for a teleport-only start, where it reaches no node. */
	WalkCosts walk;
	int node;

	public static PlanStart scene(WorldPoint tile, WalkCosts walk)
	{
		return new PlanStart(Kind.SCENE, tile, walk, WalkTable.NO_NODE);
	}

	public static PlanStart atNode(WalkTable table, int node)
	{
		return new PlanStart(Kind.AT_NODE, table.tile(node), null, node);
	}

	public static PlanStart teleportOnly(WorldPoint tile)
	{
		return teleportOnly(tile, null);
	}

	public static PlanStart teleportOnly(WorldPoint tile, WalkCosts walk)
	{
		return new PlanStart(Kind.TELEPORT_ONLY, tile, walk, WalkTable.NO_NODE);
	}
}
