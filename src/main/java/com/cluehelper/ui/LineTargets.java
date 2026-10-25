package com.cluehelper.ui;

import com.cluehelper.guide.StepGuide;
import com.cluehelper.route.Ground;
import com.cluehelper.route.LoadedScene;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.route.SceneDoors;
import com.cluehelper.route.WalkCosts;
import com.cluehelper.route.WalkGoal;
import java.util.Comparator;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Of the step's outlined objects or NPCs (a bank's booths, its bankers), the one the path line leads to, which is also
 * the only one outlined. Client thread.
 */
@Singleton
final class LineTargets
{
	private final Client client;
	private final StepGuide guide;
	private final RoutePlanner routes;
	private final SceneDoors doors;

	private WorldPoint instanceTile;
	private WalkCosts instanceWalk;

	@Inject
	LineTargets(Client client, StepGuide guide, RoutePlanner routes, SceneDoors doors)
	{
		this.client = client;
		this.guide = guide;
		this.routes = routes;
		this.doors = doors;
	}

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

	private static WorldPoint beside(WorldView view, WalkCosts here, TileObject object)
	{
		if (!(object instanceof GameObject))
		{
			return object.getWorldLocation();
		}
		WalkGoal goal = objectGoal(view, object);
		WorldPoint side = here.cheapestBeside(goal.getSouthWest(), goal.getSizeX(), goal.getSizeY());
		return side != null ? side : object.getWorldLocation();
	}
}
