package com.cluehelper.ui;

import com.cluehelper.route.Ground;
import com.cluehelper.route.PlannerRunner;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.route.WalkCosts;
import com.cluehelper.route.WalkData;
import com.cluehelper.route.WalkGoal;
import com.cluehelper.route.WorldWalk;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;

/**
 * The walk the path line draws, searched off the client thread, on its own runner so it never supersedes a route plan:
 * once per goal, and again once the player leaves it. Client thread.
 */
final class WalkLine
{
	/** A player this far from every tile of the walk took another way. */
	private static final int OFF_WALK_TILES = 2;

	private final Client client;
	private final RoutePlanner routes;
	private final WorldWalk walker;
	private final PlannerRunner runner;

	private WalkGoal goal;
	private WalkCosts here;
	private List<WorldPoint> walk = List.of();
	/** The index of the walk's tile the player last stood nearest. */
	private int passed;
	private boolean stale;

	@Inject
	WalkLine(Client client, RoutePlanner routes, WorldWalk walker, ScheduledExecutorService executor, ClientThread clientThread)
	{
		this.client = client;
		this.routes = routes;
		this.walker = walker;
		this.runner = new PlannerRunner(executor, clientThread);
	}

	/** Searches the walk to {@code goal} again when it is new, left, or stale; the walk found last stays until then. */
	void follow(WorldView view, WalkCosts here, WalkGoal goal)
	{
		boolean newGoal = !goal.equals(this.goal);
		boolean again = stale || isLeft() || walk.isEmpty() && here != this.here;
		if (newGoal || again && !runner.isWorking())
		{
			search(view, here, goal);
		}
	}

	/** The walk found last, from where the player stood then; it stands while the next search runs (a wandering NPC). */
	List<WorldPoint> getWalk()
	{
		return walk;
	}

	/**
	 * Moves on along the walk to its tile nearest the player, never back: past a door, the tile before it can be as near
	 * through the wall, and the door would count as ahead again.
	 */
	void advance()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		WorldPoint at = local.getWorldLocation();
		int nearestDistance = Integer.MAX_VALUE;
		for (int i = passed; i < walk.size(); i++)
		{
			int distance = walk.get(i).getPlane() == at.getPlane() ? walk.get(i).distanceTo2D(at) : Integer.MAX_VALUE;
			if (distance <= nearestDistance)
			{
				passed = i;
				nearestDistance = distance;
			}
		}
	}

	/** The walk from the player on: from the tile {@link #advance} reached. */
	List<WorldPoint> getAhead()
	{
		return walk.subList(Math.min(passed, walk.size()), walk.size());
	}

	/** After a gate, cart or shortcut the player may have gone back the way they came. */
	void forget()
	{
		stale = true;
	}

	private void search(WorldView view, WalkCosts here, WalkGoal goal)
	{
		this.goal = goal;
		this.here = here;
		stale = false;
		Player local = client.getLocalPlayer();
		WalkData.Loaded data = routes.getWalk();
		if (local == null || data == null)
		{
			return;
		}
		Ground ground = view.isInstance() ? Ground.sceneOnly(here.getScene())
			: Ground.world(data.getMap(), here.getScene(), data.getTable(), routes.openRows());
		WorldPoint from = local.getWorldLocation();
		runner.submit(() -> walker.path(ground, from, goal), found ->
		{
			walk = found;
			passed = 0;
		});
	}

	private boolean isLeft()
	{
		Player local = client.getLocalPlayer();
		if (local == null || walk.isEmpty())
		{
			return false;
		}
		WorldPoint at = local.getWorldLocation();
		return walk.stream().noneMatch(tile -> tile.getPlane() == at.getPlane() && tile.distanceTo2D(at) <= OFF_WALK_TILES);
	}
}
