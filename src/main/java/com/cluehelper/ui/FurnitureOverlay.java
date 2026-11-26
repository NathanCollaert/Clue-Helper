package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.link.ClueLink;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.*;

public class FurnitureOverlay extends Overlay
{
	/** A tile polygon's corners run south-west, south-east, north-east, north-west: each side faces this neighbour. */
	private static final int[] SIDE_DX = {0, 1, 0, -1};
	private static final int[] SIDE_DY = {-1, 0, 1, 0};
	@Inject
	StepGuide guide;
	@Inject
	ClueHelperConfig config;
	@Inject
	Highlighter highlighter;
	@Inject
	LineTargets targets;
	@Inject
	Client client;
	@Inject
	ClueLink clueLink;
	@Inject
	ItemManager itemManager;

	FurnitureOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.highlightMenus())
		{
			return null;
		}
		WorldView view = client.getTopLevelWorldView();
		TileObject object = view == null ? null : targets.object(view);
		int usedItem = guide.getItemUsedOnObjects();
		if (object != null && (usedItem >= 0 || guide.isPlantingPatch()))
		{
			BufferedImage pile = usedItem < 0 ? null : itemManager.getImage(usedItem, guide.getItemUsedOnObjectsQuantity(usedItem), false);
			drawItemOn(graphics, guide.getHighlightObjects(), pile);
		}
		else if (object != null)
		{
			highlighter.object(graphics, object, guide.getFillObjects().contains(object));
		}
		NPC npc = targets.npc();
		if (npc != null && !clueLink.coreMarks(npc))
		{
			highlighter.npc(graphics, npc, guide.getFillNpcs().contains(npc));
		}
		return null;
	}

	/**
	 * Objects an item is used on are marked as a hot/cold spot is: one outline around the ground all their one-tile pieces
	 * cover (the crevice is nine, a patch one per tile), the item, if any, shown near its middle. Their models would trace
	 * every plant on a patch.
	 */
	private void drawItemOn(Graphics2D graphics, List<TileObject> objects, BufferedImage icon)
	{
		Set<WorldPoint> tiles = objects.stream().map(TileObject::getWorldLocation).collect(Collectors.toSet());
		Path2D outline = new Path2D.Float();
		objects.forEach(piece -> addOuterSides(outline, piece, tiles));
		if (outline.getCurrentPoint() == null)
		{
			return;
		}
		highlighter.tile(graphics, outline);
		if (icon == null)
		{
			return;
		}
		Rectangle middle = middlePiece(objects, outline.getBounds());
		graphics.drawImage(icon, (int) middle.getCenterX() - icon.getWidth() / 2, (int) middle.getCenterY() - icon.getHeight() / 2, null);
	}

	/** The piece nearest the outline's middle, where the item is shown: an L-shaped patch's middle lies outside it. */
	private static Rectangle middlePiece(List<TileObject> objects, Rectangle outline)
	{
		return objects.stream().map(TileObject::getCanvasTilePoly).filter(Objects::nonNull).map(Polygon::getBounds)
			.min(Comparator.comparingDouble(piece -> Point2D.distanceSq(piece.getCenterX(), piece.getCenterY(), outline.getCenterX(), outline.getCenterY())))
			.orElse(outline);
	}

	/** The sides of the piece's tile that no other piece's tile shares; drawn side by side, rather than joined each frame. */
	private static void addOuterSides(Path2D outline, TileObject piece, Set<WorldPoint> tiles)
	{
		Polygon tile = piece.getCanvasTilePoly();
		if (tile == null || tile.npoints != SIDE_DX.length)
		{
			return;
		}
		WorldPoint at = piece.getWorldLocation();
		for (int side = 0; side < SIDE_DX.length; side++)
		{
			if (!tiles.contains(at.dx(SIDE_DX[side]).dy(SIDE_DY[side])))
			{
				int end = (side + 1) % SIDE_DX.length;
				outline.moveTo(tile.xpoints[side], tile.ypoints[side]);
				outline.lineTo(tile.xpoints[end], tile.ypoints[end]);
			}
		}
	}
}
