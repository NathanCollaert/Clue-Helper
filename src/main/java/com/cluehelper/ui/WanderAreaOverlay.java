package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.guide.SubStep;
import com.cluehelper.link.ClueLink;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepIndex;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The edge of a wide wanderer's range on the ground (Wingstone's desert), so the player knows where to look, and of the
 * area a task works anywhere in (the Dark Altar's reanimation ground) while walking there or doing it.
 */
public class WanderAreaOverlay extends Overlay
{
	private static final int PLANE = 4;
	private static final Stroke EDGE = new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{8, 8}, 0);

	private final Client client;
	private final ClueLink clueLink;
	private final ClueHelperConfig config;
	private final StepGuide guide;

	@Inject
	WanderAreaOverlay(Client client, ClueLink clueLink, ClueHelperConfig config, StepGuide guide)
	{
		this.guide = guide;
		this.client = client;
		this.clueLink = clueLink;
		this.config = config;
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
		graphics.setColor(Markers.COLOUR);
		graphics.setStroke(EDGE);
		Step step = clueLink.getCurrentStep();
		StepIndex.Wander range = step == null ? null : step.getData().getWander();
		if (range != null && view.getPlane() == range.getPlane())
		{
			drawBox(graphics, view, range.getWest(), range.getSouth(), range.getEast(), range.getNorth());
			int[] exclude = range.getExclude();
			if (exclude != null)
			{
				drawBox(graphics, view, exclude[0], exclude[1], exclude[2], exclude[3]);
			}
		}
		SubStep current = guide.getCurrent();
		for (StepIndex.Place place : current == null ? List.<StepIndex.Place>of() : current.getSpans())
		{
			int[] span = place.getSpan();
			if (view.getPlane() == span[PLANE])
			{
				drawBox(graphics, view, span[0], span[1], span[2], span[3]);
			}
		}
		return null;
	}

	/** Around the tiles from west, south to east, north inclusive. */
	private void drawBox(Graphics2D graphics, WorldView view, int west, int south, int eastTile, int northTile)
	{
		int east = eastTile + 1;
		int north = northTile + 1;
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
			if (previous != null && at != null)
			{
				graphics.drawLine(previous.getX(), previous.getY(), at.getX(), at.getY());
			}
			previous = at;
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
