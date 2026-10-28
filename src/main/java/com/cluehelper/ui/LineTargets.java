package com.cluehelper.ui;

import com.cluehelper.guide.StepGuide;
import com.cluehelper.route.*;
import java.util.Comparator;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

/**
 * Of the step's outlined objects or NPCs (a bank's booths, its bankers), the one the path line leads to, which is also
 * the only one outlined. Client thread.
 */
@Singleton
final class LineTargets
{
	@Inject
	private Client client;
	@Inject
	private StepGuide guide;
	@Inject
	private RoutePlanner routes;
	@Inject
	private SceneDoors doors;

	private WorldPoint instanceTile;
	private WalkCosts instanceWalk;

	/** The outlined object cheapest to walk to: the game walks to the nearest side of the booth or chest clicked. */
	TileObject object(WorldView view)
	{
		WalkCosts here = walk(view);
		TileObject best = null;
		int bestCost = WalkCosts.UNREACHED;
		for (TileObject object : guide.getHighlightObjects())
		{
			int cost = here == null ? WalkCosts.UNREACHED : here.cost(beside(view, here, object));
			if (best == null || cost < bestCost)
			{
				best = object;
				bestCost = cost;
			}
		}
		return best;
	}

	/** The outlined NPC nearest the player. */
	NPC npc()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return null;
		}
		WorldPoint at = local.getWorldLocation();
		return guide.getHighlightNpcs().stream().min(Comparator.comparingInt(npc -> npc.getWorldLocation().distanceTo2D(at)))
			.orElse(null);
	}

	/** The walk from the player: the planner's start walk, or in an instance (a house) its scene's own. */
	WalkCosts walk(WorldView view)
	{
		return view.isInstance() ? instanceWalk(view) : planWalk();
	}

	WalkCosts planWalk()
	{
		PlanStart start = routes.getStart();
		return start == null ? null : start.getWalk();
	}

	/** The object itself is walked to, as the game walks when it is clicked. */
	static WalkGoal objectGoal(WorldView view, TileObject object)
	{
		WorldPoint[] sides = wallSides(object);
		if (sides != null)
		{
			WorldPoint southWest = new WorldPoint(Math.min(sides[0].getX(), sides[1].getX()), Math.min(sides[0].getY(), sides[1].getY()),
				sides[0].getPlane());
			return WalkGoal.area(southWest, 1 + Math.abs(sides[0].getX() - sides[1].getX()), 1 + Math.abs(sides[0].getY() - sides[1].getY()));
		}
		if (!(object instanceof GameObject))
		{
			return WalkGoal.object(object.getWorldLocation(), 1, 1);
		}
		GameObject game = (GameObject) object;
		Point min = game.getSceneMinLocation();
		return WalkGoal.object(WorldPoint.fromScene(view, min.getX(), min.getY(), game.getPlane()), game.sizeX(), game.sizeY());
	}

	private WalkCosts instanceWalk(WorldView view)
	{
		Player local = client.getLocalPlayer();
		WorldPoint at = local == null ? null : local.getWorldLocation();
		if (at != null && !at.equals(instanceTile))
		{
			LoadedScene scene = doors.withDoorsOpen(view, LoadedScene.captureLoaded(view), null);
			instanceTile = at;
			instanceWalk = scene == null ? null : WalkCosts.from(Ground.sceneOnly(scene), at);
		}
		return instanceWalk;
	}

	/**
	 * A door in a wall (the Pyramid Plunder lobby's Tomb Door) is used from its own tile or the one across the edge it
	 * stands on; the edge between them is the wall itself, so "beside it with no wall between" finds neither. Null for
	 * anything but a straight wall.
	 */
	private static WorldPoint[] wallSides(TileObject object)
	{
		if (!(object instanceof WallObject))
		{
			return null;
		}
		WorldPoint at = object.getWorldLocation();
		switch (((WallObject) object).getOrientationA())
		{
			case SceneDoors.WEST:
				return new WorldPoint[] {at, at.dx(-1)};
			case SceneDoors.NORTH:
				return new WorldPoint[] {at, at.dy(1)};
			case SceneDoors.EAST:
				return new WorldPoint[] {at, at.dx(1)};
			case SceneDoors.SOUTH:
				return new WorldPoint[] {at, at.dy(-1)};
			default:
				return null;
		}
	}

	private static WorldPoint beside(WorldView view, WalkCosts here, TileObject object)
	{
		WorldPoint[] sides = wallSides(object);
		if (sides != null)
		{
			return here.cost(sides[0]) <= here.cost(sides[1]) ? sides[0] : sides[1];
		}
		if (!(object instanceof GameObject))
		{
			return object.getWorldLocation();
		}
		WalkGoal goal = objectGoal(view, object);
		WorldPoint side = here.cheapestBeside(goal.getSouthWest(), goal.getSizeX(), goal.getSizeY());
		return side != null ? side : object.getWorldLocation();
	}
}
