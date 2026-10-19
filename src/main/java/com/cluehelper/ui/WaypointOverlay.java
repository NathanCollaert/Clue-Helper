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
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * The current sub-step's waypoint in the scene (PLAN §4 item 7): its tile, our own arrow above it, and the
 * path line from the player. Waypoints are never core's target, so the line ends where core's marker takes over.
 */
public class WaypointOverlay extends Overlay
{
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
		if (config.pathLine())
		{
			drawPath(graphics, view, waypoint);
		}
		LocalPoint local = LocalPoint.fromWorld(view, waypoint);
		if (local == null)
		{
			return null;
		}
		Polygon tile = Perspective.getCanvasTilePoly(client, local);
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

	/** The game's hint arrow already points there (as Quest Helper's NpcStep checks): ours would double it. */
	private boolean hintArrowAt(WorldPoint waypoint)
	{
		return client.hasHintArrow() && waypoint.equals(client.getHintArrowPoint());
	}

	private void drawPath(Graphics2D graphics, WorldView view, WorldPoint waypoint)
	{
		PlanStart start = routes.getStart();
		SceneBfs.Result scene = start == null ? null : start.getScene();
		if (scene == null)
		{
			return;
		}
		if (scene != pathScene || !waypoint.equals(pathGoal))
		{
			pathScene = scene;
			pathGoal = waypoint;
			path = scene.pathTo(waypoint);
		}
		graphics.setColor(Markers.COLOUR);
		graphics.setStroke(new BasicStroke(PATH_STROKE));
		Point previous = null;
		for (WorldPoint tile : path)
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
