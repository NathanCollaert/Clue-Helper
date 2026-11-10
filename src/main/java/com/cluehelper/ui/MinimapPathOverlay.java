package com.cluehelper.ui;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.*;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.*;

/** The scene's path line again on the minimap, kept inside the round map so it never runs over the orbs. */
public class MinimapPathOverlay extends Overlay
{
	/** Fixed, resizable classic and resizable modern: whichever layout is showing has its map. */
	private static final int[] MINIMAPS = {InterfaceID.Toplevel.MINIMAP, InterfaceID.ToplevelOsrsStretch.MINIMAP,
		InterfaceID.ToplevelPreEoc.MINIMAP};
	private static final Stroke STROKE = new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	/** How much of each segment next to a corner bends into the curve; the rest stays straight. */
	private static final double CORNER = 0.2;

	@Inject
	private Client client;
	@Inject
	private WaypointOverlay waypoints;

	MinimapPathOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		List<WorldPoint> line = waypoints.getShownLine();
		WorldView view = client.getTopLevelWorldView();
		Shape map = minimapArea();
		if (line.isEmpty() || view == null || map == null)
		{
			return null;
		}
		Shape clip = graphics.getClip();
		Object antialiasing = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.clip(map);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(Highlighter.COLOUR);
		graphics.setStroke(STROKE);
		graphics.draw(path(minimapPoints(view, line)));
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antialiasing);
		graphics.setClip(clip);
		return null;
	}

	/** A null breaks the line: a tile off the loaded area, or a long jump (a cave into another region). */
	private List<Point> minimapPoints(WorldView view, List<WorldPoint> line)
	{
		List<Point> points = new ArrayList<>();
		WorldPoint previous = null;
		for (WorldPoint tile : line)
		{
			if (previous != null && (previous.getPlane() != tile.getPlane()
				|| previous.distanceTo2D(tile) > WaypointOverlay.CROSSING_JUMP_TILES))
			{
				points.add(null);
			}
			LocalPoint local = tile.getPlane() == view.getPlane() ? LocalPoint.fromWorld(view, tile) : null;
			points.add(local == null ? null : Perspective.localToMinimap(client, local));
			previous = tile;
		}
		return points;
	}

	private static Path2D path(List<Point> points)
	{
		Path2D path = new Path2D.Double();
		List<Point> run = new ArrayList<>();
		for (Point point : points)
		{
			if (point != null)
			{
				run.add(point);
				continue;
			}
			addRun(path, run);
			run.clear();
		}
		addRun(path, run);
		return path;
	}

	/** Straight along each segment, bending only through the last and first stretch either side of a corner. */
	private static void addRun(Path2D path, List<Point> run)
	{
		if (run.size() < 2)
		{
			return;
		}
		path.moveTo(run.get(0).getX(), run.get(0).getY());
		for (int i = 1; i + 1 < run.size(); i++)
		{
			Point before = run.get(i - 1);
			Point corner = run.get(i);
			Point after = run.get(i + 1);
			path.lineTo(towards(corner.getX(), before.getX()), towards(corner.getY(), before.getY()));
			path.quadTo(corner.getX(), corner.getY(), towards(corner.getX(), after.getX()), towards(corner.getY(), after.getY()));
		}
		Point last = run.get(run.size() - 1);
		path.lineTo(last.getX(), last.getY());
	}

	private static double towards(int corner, int other)
	{
		return corner + (other - corner) * CORNER;
	}

	private Shape minimapArea()
	{
		for (int id : MINIMAPS)
		{
			Widget minimap = client.getWidget(id);
			if (minimap != null && !minimap.isHidden())
			{
				Rectangle bounds = minimap.getBounds();
				return new Ellipse2D.Double(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
			}
		}
		return null;
	}
}
