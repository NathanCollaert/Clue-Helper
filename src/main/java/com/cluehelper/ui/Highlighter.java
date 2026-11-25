package com.cluehelper.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
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
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.ColorUtil;
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
	/** Where to stand: pale ice, close to the cyan of the line that leads there but apart from it. */
	private static final Color TARGET = new Color(170, 235, 255, 230);
	private static final int RIPPLE_ALPHA = 140;
	private static final int RIPPLES = 2;
	private static final long RIPPLE_MILLIS = 3200;
	/** How much bigger than the tile a ring is when it has faded away. */
	private static final double RIPPLE_GROWTH = 0.3;
	/** A ring spreads no further than this past a larger shape (a whole patch): about as far as past one tile up close. */
	private static final double RIPPLE_MAX_SPREAD_PIXELS = 12;
	private static final BasicStroke RIPPLE_STROKE = new BasicStroke(1.5f);
	private static final int OUTLINE_WIDTH = 2;
	private static final int FEATHER = 0;
	private static final int ARC = 6;
	private static final BasicStroke STROKE = new BasicStroke(2f);
	/**
	 * A flat click spot (two triangles) laid over scenery drawn by other objects: the Broken bridge's gap. Its outline is a
	 * sliver no one sees.
	 */
	private static final int FLAT_MARKER_FACES = 2;
	/** A model this low lies flat on the ground (an empty, raked farming patch): its outline draws nothing to see. */
	private static final int FLAT_MODEL_HEIGHT = 8;
	/**
	 * A world ring's object is only its butterflies: its mushrooms are the ground decorations on its tile and the eight
	 * around it, one piece per tile, in a copy for each part of the map. A house's ring and spirit tree carry their
	 * mushrooms themselves.
	 */
	private static final Set<Integer> FAIRY_RINGS = Set.of(ObjectID.FAIRYRING_MINORHUB, ObjectID.FAIRYRING_HOMEHUB);
	private static final int MUSHROOM_TILES = 1;

	@Inject
	Client client;
	@Inject
	ModelOutlineRenderer outlines;
	@Inject
	ItemManager itemManager;
	final Set<Integer> unmodelled = new HashSet<>();

	@Inject
	ModelSilhouette silhouette;

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
			List<TileObject> parts = FAIRY_RINGS.contains(object.getId()) ? withMushrooms(object) : List.of(object);
			for (TileObject part : parts)
			{
				if (leftClick)
				{
					graphics.setColor(ONE_CLICK_FILL);
					graphics.fill(silhouette.of(part));
				}
				outlines.drawOutline(part, OUTLINE_WIDTH, COLOUR, FEATHER);
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

	/** The ring with the ground decorations on the tiles it stands among: its mushrooms. */
	private static List<TileObject> withMushrooms(TileObject ring)
	{
		List<TileObject> parts = new ArrayList<>(List.of(ring));
		Tile[][] tiles = ring.getWorldView().getScene().getTiles()[ring.getPlane()];
		LocalPoint centre = ring.getLocalLocation();
		for (int x = centre.getSceneX() - MUSHROOM_TILES; x <= centre.getSceneX() + MUSHROOM_TILES; x++)
		{
			for (int y = centre.getSceneY() - MUSHROOM_TILES; y <= centre.getSceneY() + MUSHROOM_TILES; y++)
			{
				Tile tile = x < 0 || y < 0 || x >= tiles.length || y >= tiles[x].length ? null : tiles[x][y];
				if (tile != null && tile.getGroundObject() != null)
				{
					parts.add(tile.getGroundObject());
				}
			}
		}
		return parts;
	}

	/** Where an object the outline renderer can't draw is clicked: its clickbox, else its tile. */
	private static Shape hiddenShape(TileObject object)
	{
		Shape clickbox = object.getClickbox();
		return clickbox != null ? clickbox : object.getCanvasTilePoly();
	}

	/**
	 * A scenery or ground object (God Wars' ice bridge) with no model at all, a flat click spot or flat ground (an empty
	 * farming patch), or one whose every
	 * face is skipped by the outline renderer (see-through) or never drawn by the client. A wall or wall decoration (the
	 * Ferox Enclave's barriers) is hidden when none of its models has a drawn face.
	 */
	private static boolean isHidden(TileObject object)
	{
		if (object instanceof WallObject || object instanceof DecorativeObject)
		{
			return wallRenderables(object).stream().map(Highlighter::modelOf).filter(Objects::nonNull).noneMatch(Highlighter::hasDrawnFace);
		}
		Renderable renderable = object instanceof GameObject ? ((GameObject) object).getRenderable()
			: object instanceof GroundObject ? ((GroundObject) object).getRenderable() : null;
		if (renderable == null && !(object instanceof GameObject))
		{
			return false;
		}
		Model model = modelOf(renderable);
		if (model == null || model.getFaceCount() <= FLAT_MARKER_FACES || model.getModelHeight() <= FLAT_MODEL_HEIGHT)
		{
			return true;
		}
		return !hasDrawnFace(model);
	}

	private static List<Renderable> wallRenderables(TileObject object)
	{
		List<Renderable> renderables = new ArrayList<>();
		if (object instanceof WallObject)
		{
			renderables.add(((WallObject) object).getRenderable1());
			renderables.add(((WallObject) object).getRenderable2());
		}
		else
		{
			renderables.add(((DecorativeObject) object).getRenderable());
			renderables.add(((DecorativeObject) object).getRenderable2());
		}
		return renderables;
	}

	private static Model modelOf(Renderable renderable)
	{
		return renderable instanceof Model ? (Model) renderable : renderable == null ? null : renderable.getModel();
	}

	private static boolean hasDrawnFace(Model model)
	{
		return IntStream.range(0, model.getFaceCount()).anyMatch(face -> ModelSilhouette.isDrawn(model, face));
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
	public void itemScaled(Graphics2D graphics, int itemId, Rectangle bounds, boolean leftClick)
	{
		if (leftClick)
		{
			drawScaled(graphics, ImageUtil.fillImage(itemManager.getImage(itemId, 1, false), ONE_CLICK_FILL), bounds);
		}
		drawScaled(graphics, itemManager.getItemOutline(itemId, 1, COLOUR), bounds);
	}

	private static void drawScaled(Graphics2D graphics, BufferedImage image, Rectangle bounds)
	{
		double scale = Math.min(bounds.getWidth() / image.getWidth(), bounds.getHeight() / image.getHeight());
		int width = (int) Math.round(image.getWidth() * scale);
		int height = (int) Math.round(image.getHeight() * scale);
		graphics.drawImage(image, bounds.x + (bounds.width - width) / 2, bounds.y + (bounds.height - height) / 2, width, height, null);
	}

	public void box(Graphics2D graphics, Rectangle bounds)
	{
		box(graphics, rounded(bounds));
	}

	/** A spell, a tab stone, a fairy ring dial: things without text or a model. */
	public void box(Graphics2D graphics, Shape shape)
	{
		graphics.setColor(FILL);
		graphics.fill(shape);
		outline(graphics, shape);
	}

	/** A spell: filled when its left click casts the step's teleport, outlined when its right-click menu does. */
	public void spell(Graphics2D graphics, Rectangle bounds, boolean leftClick)
	{
		Shape shape = rounded(bounds);
		if (leftClick)
		{
			graphics.setColor(ONE_CLICK_FILL);
			graphics.fill(shape);
		}
		outline(graphics, shape);
	}

	private static Shape rounded(Rectangle bounds)
	{
		return new RoundRectangle2D.Double(bounds.x, bounds.y, bounds.width, bounds.height, ARC, ARC);
	}

	private static void outline(Graphics2D graphics, Shape shape)
	{
		graphics.setColor(COLOUR);
		graphics.setStroke(STROKE);
		graphics.draw(shape);
	}

	/** A spot to walk to with nothing on it to outline: a steady outline, with rings slowly spreading from it. */
	public void tile(Graphics2D graphics, Shape tile)
	{
		graphics.setStroke(STROKE);
		graphics.setColor(TARGET);
		graphics.draw(tile);
		double start = System.currentTimeMillis() % RIPPLE_MILLIS / (double) RIPPLE_MILLIS;
		for (int ring = 0; ring < RIPPLES; ring++)
		{
			ripple(graphics, tile, (start + (double) ring / RIPPLES) % 1);
		}
	}

	/** {@code phase} runs from 0, the ring on the tile's outline, to 1, the ring spread out and faded away. */
	private static void ripple(Graphics2D graphics, Shape tile, double phase)
	{
		Rectangle bounds = tile.getBounds();
		double easedOut = 1 - (1 - phase) * (1 - phase);
		double halfSize = Math.max(1, Math.max(bounds.getWidth(), bounds.getHeight()) / 2);
		double scale = 1 + Math.min(RIPPLE_GROWTH, RIPPLE_MAX_SPREAD_PIXELS / halfSize) * easedOut;
		AffineTransform spread = AffineTransform.getTranslateInstance(bounds.getCenterX(), bounds.getCenterY());
		spread.scale(scale, scale);
		spread.translate(-bounds.getCenterX(), -bounds.getCenterY());
		graphics.setStroke(RIPPLE_STROKE);
		graphics.setColor(ColorUtil.colorWithAlpha(TARGET, (int) (RIPPLE_ALPHA * (1 - phase))));
		graphics.draw(spread.createTransformedShape(tile));
	}
}
