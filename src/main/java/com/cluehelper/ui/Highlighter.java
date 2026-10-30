package com.cluehelper.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.stream.IntStream;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.ImageUtil;

/**
 * How every Clue Helper highlight looks, so an overlay only decides what to highlight. Whatever is clicked is outlined
 * in cyan, and filled as well when a plain left click does what the step needs. Menu text is recoloured instead (see
 * MenuLines); only a line or widget without text, or a map's place button, gets a box. An object whose faces are all
 * hidden (a click spot under the scenery that shows) has its clickbox boxed instead. Objects and NPCs on another floor
 * are not drawn: the player can't see them yet.
 */
@Singleton
@Slf4j
public class Highlighter
{
	/** Cyan, so nothing of ours can be mistaken for the Clue Scroll plugin's yellow arrow. */
	static final Color COLOUR = new Color(64, 208, 255);
	static final Color FILL = new Color(64, 208, 255, 40);
	static final Color ONE_CLICK_FILL = new Color(64, 208, 255, 90);
	/** Wide enough to show on the nexus, which is cyan itself. */
	private static final int OUTLINE_WIDTH = 4;
	private static final int FEATHER = 4;
	private static final int ARC = 6;
	private static final float STROKE = 2f;
	/**
	 * A flat click spot (two triangles) laid over scenery drawn by other objects: the Broken bridge's gap. Its outline is a
	 * sliver no one sees.
	 */
	private static final int FLAT_MARKER_FACES = 2;
	/** A model this low lies flat on the ground (an empty, raked farming patch): its outline draws nothing to see. */
	private static final int FLAT_MODEL_HEIGHT = 8;
	/**
	 * The outline takes only a world ring's butterflies, not its mushrooms: a circle on the ground marks them. A house's
	 * ring and spirit tree outline their mushrooms themselves.
	 */
	private static final Set<Integer> FAIRY_RINGS = Set.of(ObjectID.FAIRYRING_MINORHUB, ObjectID.FAIRYRING_HOMEHUB);
	/** In local units: a tile is 128. */
	private static final int RING_RADIUS = 150;
	private static final int RING_POINTS = 32;

	@Inject
	private Client client;
	@Inject
	private ModelOutlineRenderer outlines;
	@Inject
	private ItemManager itemManager;
	private final Set<Integer> unmodelled = new HashSet<>();

	@Inject
	private ModelSilhouette silhouette;

	/**
	 * The client's own getModel throws a NullPointerException for some dynamic objects (seen at Trollheim and after
	 * teleports), in our fill and in RuneLite's outline renderer alike; such an object goes unhighlighted that frame.
	 */
	public void object(Graphics2D graphics, TileObject object, boolean leftClick)
	{
		if (!onPlayersFloor(object.getPlane()))
		{
			return;
		}
		try
		{
			Shape hiddenShape = isHidden(object) ? hiddenShape(object) : null;
			if (hiddenShape != null)
			{
				box(graphics, hiddenShape);
				return;
			}
			if (leftClick)
			{
				graphics.setColor(ONE_CLICK_FILL);
				graphics.fill(silhouette.of(object));
			}
			outlines.drawOutline(object, OUTLINE_WIDTH, COLOUR, FEATHER);
			if (FAIRY_RINGS.contains(object.getId()))
			{
				groundRing(graphics, object);
			}
		}
		catch (NullPointerException e)
		{
			if (unmodelled.add(object.getId()))
			{
				log.warn("No model to highlight object {} at {}", object.getId(), object.getWorldLocation(), e);
			}
		}
	}

	/** The mushrooms' circle on the ground round the object, outlined: the ring itself, not a box about it. */
	private void groundRing(Graphics2D graphics, TileObject object)
	{
		LocalPoint centre = object.getLocalLocation();
		Polygon ring = new Polygon();
		for (int i = 0; i < RING_POINTS; i++)
		{
			double angle = 2 * Math.PI * i / RING_POINTS;
			LocalPoint at = new LocalPoint(centre.getX() + (int) (RING_RADIUS * Math.cos(angle)),
				centre.getY() + (int) (RING_RADIUS * Math.sin(angle)), centre.getWorldView());
			Point point = Perspective.localToCanvas(client, at, object.getPlane());
			if (point == null)
			{
				return;
			}
			ring.addPoint(point.getX(), point.getY());
		}
		graphics.setColor(COLOUR);
		graphics.setStroke(new BasicStroke(STROKE));
		graphics.draw(ring);
	}

	/** Where an object the outline renderer can't draw is clicked: its clickbox, else its tile. */
	private static Shape hiddenShape(TileObject object)
	{
		Shape clickbox = object.getClickbox();
		return clickbox != null ? clickbox : object.getCanvasTilePoly();
	}

	/**
	 * A scenery object with no model at all, a flat click spot or flat ground (an empty farming patch), or one whose every
	 * face is skipped by the outline renderer (see-through) or never drawn by the client.
	 */
	private static boolean isHidden(TileObject object)
	{
		if (!(object instanceof GameObject))
		{
			return false;
		}
		Renderable renderable = ((GameObject) object).getRenderable();
		Model model = renderable instanceof Model ? (Model) renderable : renderable == null ? null : renderable.getModel();
		if (model == null || model.getFaceCount() <= FLAT_MARKER_FACES || model.getModelHeight() <= FLAT_MODEL_HEIGHT)
		{
			return true;
		}
		return IntStream.range(0, model.getFaceCount()).noneMatch(face -> ModelSilhouette.isDrawn(model, face));
	}

	public void npc(Graphics2D graphics, NPC npc, boolean leftClick)
	{
		// An NPC that despawned since the last search has no model, which the outline renderer doesn't check.
		if (npc.getModel() != null && onPlayersFloor(npc.getWorldLocation().getPlane()))
		{
			if (leftClick)
			{
				graphics.setColor(ONE_CLICK_FILL);
				graphics.fill(silhouette.of(npc));
			}
			outlines.drawOutline(npc, OUTLINE_WIDTH, COLOUR, FEATHER);
		}
	}

	private boolean onPlayersFloor(int plane)
	{
		WorldView view = client.getTopLevelWorldView();
		return view != null && plane == view.getPlane();
	}

	public void item(Graphics2D graphics, int itemId, int quantity, Rectangle bounds, boolean leftClick)
	{
		if (leftClick)
		{
			BufferedImage image = itemManager.getImage(itemId, quantity, false);
			graphics.drawImage(ImageUtil.fillImage(image, ONE_CLICK_FILL), bounds.x, bounds.y, null);
		}
		graphics.drawImage(itemManager.getItemOutline(itemId, quantity, COLOUR), bounds.x, bounds.y, null);
	}

	/** An item's outline scaled into {@code bounds}, for a picture larger than the inventory icon (a scroll book entry). */
	public void itemScaled(Graphics2D graphics, int itemId, Rectangle bounds)
	{
		BufferedImage outline = itemManager.getItemOutline(itemId, 1, COLOUR);
		double scale = Math.min(bounds.getWidth() / outline.getWidth(), bounds.getHeight() / outline.getHeight());
		int width = (int) Math.round(outline.getWidth() * scale);
		int height = (int) Math.round(outline.getHeight() * scale);
		graphics.drawImage(outline, bounds.x + (bounds.width - width) / 2, bounds.y + (bounds.height - height) / 2, width, height, null);
	}

	public void box(Graphics2D graphics, Rectangle bounds)
	{
		box(graphics, new RoundRectangle2D.Double(bounds.x, bounds.y, bounds.width, bounds.height, ARC, ARC));
	}

	/** A spell, a tab stone, a fairy ring dial: things without text or a model. */
	public void box(Graphics2D graphics, Shape shape)
	{
		graphics.setColor(FILL);
		graphics.fill(shape);
		graphics.setColor(COLOUR);
		graphics.setStroke(new BasicStroke(STROKE));
		graphics.draw(shape);
	}

	/** A spot to walk to with nothing on it to outline. */
	public void tile(Graphics2D graphics, Polygon tile)
	{
		OverlayUtil.renderPolygon(graphics, tile, COLOUR);
	}
}
