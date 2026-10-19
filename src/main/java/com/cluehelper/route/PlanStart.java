package com.cluehelper.route;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** Where a plan starts and how its first walk is costed (PLAN §5.4 Walk edges). */
@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PlanStart
{
	public enum Kind
	{
		/** Start legs from a BFS over the loaded scene. */
		SCENE,
		/** Standing on a walk-table node: its table entries are the start legs. */
		AT_NODE,
		/** No walk leg (an instance, a boat, nothing in the scene): routes start with a teleport. */
		TELEPORT_ONLY
	}

	Kind kind;
	WorldPoint tile;
	/** For {@link Kind#SCENE}. */
	SceneBfs.Result scene;
	/** For {@link Kind#AT_NODE}. */
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
		return new PlanStart(Kind.TELEPORT_ONLY, tile, null, WalkTable.NO_NODE);
	}
}
