package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.SubStep;
import com.cluehelper.guide.StepGuide;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.AffineTransform;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The current waypoint on the minimap, as our own overlay (never a hint arrow): a diamond while it is on the
 * minimap, else an arrow at the minimap's edge pointing towards it.
 */
public class MinimapMarkerOverlay extends Overlay
{
	/** Pixels from the minimap's centre to just inside its rim, in both fixed and resizable layouts. */
	private static final int MINIMAP_RADIUS = 64;
	/**
	 * A far waypoint is projected this many tiles out, keeping its direction: localToMinimap gives null past 50 tiles,
	 * and squares its distance in an int.
	 */
	private static final int PROJECT_TILES = 40;
	private static final int EDGE_ARROW = 7;

	private final Client client;
	private final StepGuide guide;
	private final ClueHelperConfig config;

	@Inject
	MinimapMarkerOverlay(Client client, StepGuide guide, ClueHelperConfig config)
	{
		this.client = client;
		this.guide = guide;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		SubStep current = guide.getCurrent();
		Player local = client.getLocalPlayer();
		WorldPoint waypoint = current == null ? null : current.getWaypoint();
		if (!config.minimapMarker() || waypoint == null || local == null)
		{
			return null;
		}
		WorldPoint here = local.getWorldLocation();
		if (here == null || here.getPlane() != waypoint.getPlane())
		{
			return null;
		}
		int dxTiles = waypoint.getX() - here.getX();
		int dyTiles = waypoint.getY() - here.getY();
		double cap = Math.min(1, (double) PROJECT_TILES / Math.max(1, Math.max(Math.abs(dxTiles), Math.abs(dyTiles))));
		LocalPoint from = local.getLocalLocation();
		LocalPoint at = from.plus((int) Math.round(dxTiles * cap * Perspective.LOCAL_TILE_SIZE),
			(int) Math.round(dyTiles * cap * Perspective.LOCAL_TILE_SIZE));
		Point mark = Perspective.localToMinimap(client, at);
		Point centre = Perspective.localToMinimap(client, from);
		if (mark == null || centre == null)
		{
			return null;
		}
		double dx = mark.getX() - centre.getX();
		double dy = mark.getY() - centre.getY();
		double pixels = Math.hypot(dx, dy);
		if (pixels <= MINIMAP_RADIUS)
		{
			graphics.drawImage(Markers.DIAMOND_IMAGE, mark.getX() - Markers.DIAMOND_IMAGE.getWidth() / 2,
				mark.getY() - Markers.DIAMOND_IMAGE.getHeight() / 2, null);
		}
		else
		{
			double scale = MINIMAP_RADIUS / pixels;
			Point edge = new Point(centre.getX() + (int) Math.round(dx * scale), centre.getY() + (int) Math.round(dy * scale));
			drawEdgeArrow(graphics, edge, Math.atan2(dy, dx));
		}
		return null;
	}

	private static void drawEdgeArrow(Graphics2D graphics, Point at, double angle)
	{
		Polygon arrow = new Polygon(new int[]{EDGE_ARROW, -EDGE_ARROW, -EDGE_ARROW}, new int[]{0, -EDGE_ARROW, EDGE_ARROW}, 3);
		AffineTransform saved = graphics.getTransform();
		graphics.translate(at.getX(), at.getY());
		graphics.rotate(angle);
		graphics.setColor(Markers.COLOUR);
		graphics.fillPolygon(arrow);
		graphics.setColor(Markers.OUTLINE);
		graphics.drawPolygon(arrow);
		graphics.setTransform(saved);
	}
}
