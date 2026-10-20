package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.SubStep;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.route.SceneBfs;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * The current sub-step's waypoint in the scene (PLAN §4 item 7): its tile, our own arrow above it, and the
 * path line from the player; at a bank, the arrow sits on the outlined booth or chest and the path ends beside it. Waypoints are never core's target, so the line ends where core's marker takes over.
 */
public class WaypointOverlay extends Overlay
{
	/** Within this of a bank stop where only the banker is outlined, the outline is enough: no tile, arrow or path. */
	private static final int NEAR_BANK_TILES = 6;
	private static final int ARROW_Z_OFFSET = 180;
	private static final float PATH_STROKE = 2f;

	private final Client client;
	private final StepGuide guide;
	private final RoutePlanner routes;
	private final ClueHelperConfig config;

	private SceneBfs.Result pathScene;
	private WorldPoint pathGoal;
	private List<WorldPoint> path = List.of();

	@Inject
	WaypointOverlay(Client client, StepGuide guide, RoutePlanner routes, ClueHelperConfig config)
	{
		this.client = client;
		this.guide = guide;
		this.routes = routes;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		SubStep current = guide.getCurrent();
		WorldView view = client.getTopLevelWorldView();
		WorldPoint waypoint = current == null ? null : current.getWaypoint();
		if (waypoint == null || view == null || waypoint.getPlane() != view.getPlane())
		{
			return null;
		}
		SceneBfs.Result scene = planScene();
		TileObject bank = bankOutlined() ? cheapestBank(view, scene) : null;
		WorldPoint goal = bank == null ? waypoint : beside(view, scene, bank);
		if (bank == null && besideOutlinedBanker(waypoint) || bank != null && standingOn(goal))
		{
			return null;
		}
		if (config.pathLine() && scene != null)
		{
			drawPath(graphics, view, scene, goal);
		}
		if (bank != null)
		{
			OverlayUtil.renderImageLocation(client, graphics, bank.getLocalLocation(), Markers.ARROW_IMAGE, ARROW_Z_OFFSET);
			return null;
		}
		LocalPoint local = LocalPoint.fromWorld(view, waypoint);
		if (local == null)
		{
			return null;
		}
		Polygon tile = bankOutlined() ? null : Perspective.getCanvasTilePoly(client, local);
		if (tile != null)
		{
			OverlayUtil.renderPolygon(graphics, tile, Markers.COLOUR);
		}
		if (!hintArrowAt(waypoint))
		{
			OverlayUtil.renderImageLocation(client, graphics, local, Markers.ARROW_IMAGE, ARROW_Z_OFFSET);
		}
		return null;
	}

	/** Near a bank where only the banker is outlined: the outline is the guidance, a walk there is noise (B16). */
	private boolean besideOutlinedBanker(WorldPoint waypoint)
	{
		Player local = client.getLocalPlayer();
		return bankOutlined() && local != null && local.getWorldLocation().distanceTo2D(waypoint) <= NEAR_BANK_TILES;
	}

	/** The booth, chest or banker is outlined instead of the tile in front of it (Nathan, B16). */
	private boolean bankOutlined()
	{
		return guide.isBankStop() && !(guide.getHighlightObjects().isEmpty() && guide.getHighlightNpcs().isEmpty());
	}

	/** The game's hint arrow already points there (as Quest Helper's NpcStep checks): ours would double it. */
	private boolean hintArrowAt(WorldPoint waypoint)
	{
		return client.hasHintArrow() && waypoint.equals(client.getHintArrowPoint());
	}

	/** The part of the path still ahead, from the tile nearest the player on: it shortens as they walk (Nathan, B16). */
	private List<WorldPoint> ahead(List<WorldPoint> path)
	{
		Player local = client.getLocalPlayer();
		if (local == null || path.isEmpty())
		{
			return path;
		}
		WorldPoint at = local.getWorldLocation();
		int nearest = 0;
		int nearestDistance = Integer.MAX_VALUE;
		for (int i = 0; i < path.size(); i++)
		{
			int distance = path.get(i).distanceTo2D(at);
			if (distance <= nearestDistance)
			{
				nearest = i;
				nearestDistance = distance;
			}
		}
		return path.subList(nearest, path.size());
	}

	/** The booth or chest the walk reaches first: the game walks to the nearest side of the one clicked (B16). */
	private TileObject cheapestBank(WorldView view, SceneBfs.Result scene)
	{
		TileObject best = null;
		int bestCost = SceneBfs.UNREACHED;
		for (TileObject object : guide.getHighlightObjects())
		{
			int cost = scene == null ? SceneBfs.UNREACHED : scene.cost(beside(view, scene, object));
			if (best == null || cost < bestCost)
			{
				best = object;
				bestCost = cost;
			}
		}
		return best;
	}

	/** On the tile the game walks to for the chest: the outline is all that is left to show (Nathan, B16). */
	private boolean standingOn(WorldPoint tile)
	{
		Player local = client.getLocalPlayer();
		return local != null && local.getWorldLocation().equals(tile);
	}

	private static WorldPoint beside(WorldView view, SceneBfs.Result scene, TileObject object)
	{
		if (scene == null || !(object instanceof GameObject))
		{
			return object.getWorldLocation();
		}
		GameObject game = (GameObject) object;
		Point min = game.getSceneMinLocation();
		WorldPoint southWest = WorldPoint.fromScene(view, min.getX(), min.getY(), game.getPlane());
		WorldPoint side = scene.cheapestBeside(southWest, game.sizeX(), game.sizeY());
		return side != null ? side : object.getWorldLocation();
	}

	private SceneBfs.Result planScene()
	{
		PlanStart start = routes.getStart();
		return start == null ? null : start.getScene();
	}

	private void drawPath(Graphics2D graphics, WorldView view, SceneBfs.Result scene, WorldPoint waypoint)
	{
		if (scene != pathScene || !waypoint.equals(pathGoal))
		{
			pathScene = scene;
			pathGoal = waypoint;
			path = scene.pathTo(waypoint);
		}
		graphics.setColor(Markers.COLOUR);
		graphics.setStroke(new BasicStroke(PATH_STROKE));
		Point previous = null;
		for (WorldPoint tile : ahead(path))
		{
			LocalPoint local = LocalPoint.fromWorld(view, tile);
			Point at = local == null ? null : Perspective.localToCanvas(client, local, view.getPlane());
			if (previous != null && at != null)
			{
				graphics.drawLine(previous.getX(), previous.getY(), at.getX(), at.getY());
			}
			previous = at;
		}
	}
}
