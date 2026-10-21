package com.cluehelper.route;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
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
	/** The loaded scene's walk from the tile; also kept for a teleport-only start, where it reaches no node. */
	SceneBfs.Result scene;
	int node;

	public static PlanStart scene(WorldPoint tile, SceneBfs.Result scene)
	{
		return new PlanStart(Kind.SCENE, tile, scene, WalkTable.NO_NODE);
	}

	public static PlanStart atNode(WalkTable table, int node)
	{
		return new PlanStart(Kind.AT_NODE, table.tile(node), null, node);
	}

	public static PlanStart teleportOnly(WorldPoint tile)
	{
		return teleportOnly(tile, null);
	}

	public static PlanStart teleportOnly(WorldPoint tile, SceneBfs.Result scene)
	{
		return new PlanStart(Kind.TELEPORT_ONLY, tile, scene, WalkTable.NO_NODE);
	}
}
