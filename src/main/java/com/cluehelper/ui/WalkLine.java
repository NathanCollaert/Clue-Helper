package com.cluehelper.ui;

import com.cluehelper.route.Ground;
import com.cluehelper.route.LoadedScene;
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
	/** A player this far from every tile of the walk still ahead took another way, or went back over an obstacle. */
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
	 * Moves on along the walk to its tile nearest the player, never back. Of tiles as near, the later one wins (past a
	 * door, the tile before it can be as near through the wall, and the door would count as ahead again), unless the
	 * player can't step to it yet.
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
			WorldPoint tile = walk.get(i);
			int distance = tile.getPlane() == at.getPlane() ? tile.distanceTo2D(at) : Integer.MAX_VALUE;
			if (distance < nearestDistance || distance == nearestDistance && !isClosedOff(at, tile))
			{
				passed = i;
				nearestDistance = distance;
			}
		}
	}

	/**
	 * A neighbour the game won't step to now. The line is laid with doors open, so it can cross a doorway diagonally and
	 * miss the tile in front of the door: standing there, the tile behind a door not yet opened is as near as the one
	 * before it, but not passed.
	 */
	private boolean isClosedOff(WorldPoint at, WorldPoint tile)
	{
		if (tile.getPlane() != at.getPlane() || tile.distanceTo2D(at) != 1)
		{
			return false;
		}
		LoadedScene live = LoadedScene.capture(client.getTopLevelWorldView());
		return live != null && live.contains(at) && live.contains(tile) && !live.canStep(at, tile);
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

	/**
	 * A goal searched again from a tile no walk leaves (a stepping stone mid-shortcut) finds nothing: the walk found
	 * before stands. A new goal out of reach still clears it, never leading the wrong way.
	 */
	private void search(WorldView view, WalkCosts here, WalkGoal goal)
	{
		boolean sameGoal = goal.equals(this.goal);
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
			if (found.isEmpty() && sameGoal)
			{
				return;
			}
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
		return getAhead().stream().noneMatch(tile -> tile.getPlane() == at.getPlane() && tile.distanceTo2D(at) <= OFF_WALK_TILES);
	}
}
