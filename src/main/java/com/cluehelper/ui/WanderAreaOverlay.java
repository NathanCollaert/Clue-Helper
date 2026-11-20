package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.*;
import com.cluehelper.link.ClueLink;
import com.cluehelper.step.*;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.*;

/**
 * The edge of a wide wanderer's range on the ground (Wingstone's desert), so the player knows where to look, and of the
 * area a task works anywhere in (the Dark Altar's reanimation ground) while walking there or doing it.
 */
public class WanderAreaOverlay extends Overlay
{
	private static final Stroke EDGE = new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{8, 8}, 0);

	@Inject
	private Client client;
	@Inject
	private ClueLink clueLink;
	@Inject
	private ClueHelperConfig config;
	@Inject
	private StepGuide guide;

	WanderAreaOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		WorldView view = client.getTopLevelWorldView();
		if (!config.pathLine() || view == null)
		{
			return null;
		}
		graphics.setColor(Highlighter.COLOUR);
		graphics.setStroke(EDGE);
		Step step = clueLink.getCurrentStep();
		StepIndex.Wander range = step == null ? null : step.getData().getWander();
		if (range != null)
		{
			drawBox(graphics, view, range.getArea());
			drawBox(graphics, view, range.getExclude());
		}
		SubStep current = guide.getCurrent();
		for (StepIndex.Place place : current == null ? List.<StepIndex.Place>of() : current.getSpans())
		{
			drawBox(graphics, view, place.getSpan());
		}
		return null;
	}

	/** Around the box's tiles, when it is on the plane in view. */
	private void drawBox(Graphics2D graphics, WorldView view, StepIndex.Box box)
	{
		if (box == null || box.getPlane() != view.getPlane())
		{
			return;
		}
		int west = box.getWest();
		int south = box.getSouth();
		int east = box.getEast() + 1;
		int north = box.getNorth() + 1;
		drawEdge(graphics, view, west, south, east, south);
		drawEdge(graphics, view, east, south, east, north);
		drawEdge(graphics, view, east, north, west, north);
		drawEdge(graphics, view, west, north, west, south);
	}

	/** Corner to corner of the range's tiles, a tile at a time so the line follows the ground; off the scene, nothing. */
	private void drawEdge(Graphics2D graphics, WorldView view, int fromX, int fromY, int toX, int toY)
	{
		int steps = Math.max(Math.abs(toX - fromX), Math.abs(toY - fromY));
		Point previous = null;
		for (int i = 0; i <= steps; i++)
		{
			Point at = corner(view, fromX + Integer.signum(toX - fromX) * i, fromY + Integer.signum(toY - fromY) * i);
			previous = WaypointOverlay.drawTo(graphics, previous, at);
		}
	}

	/** The south-west corner of world tile x, y on the canvas. */
	private Point corner(WorldView view, int x, int y)
	{
		int sceneX = x - view.getBaseX();
		int sceneY = y - view.getBaseY();
		if (sceneX < 1 || sceneY < 1 || sceneX >= view.getSizeX() || sceneY >= view.getSizeY())
		{
			return null;
		}
		LocalPoint local = new LocalPoint(sceneX * Perspective.LOCAL_TILE_SIZE, sceneY * Perspective.LOCAL_TILE_SIZE, view);
		return Perspective.localToCanvas(client, local, view.getPlane());
	}
}
