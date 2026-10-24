package com.cluehelper.ui;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.ImageUtil;

/**
 * How every Clue Helper highlight looks, so an overlay only decides what to highlight. Whatever is clicked is outlined
 * in cyan, and filled as well when a plain left click does what the step needs. Menu text is recoloured instead (see
 * MenuLines); only a line or widget without text gets a box. Objects and NPCs on another floor are not drawn: the
 * player can't see them yet.
 */
@Singleton
@Slf4j
public class Highlighter
{
	/** Wide enough to show on the nexus, which is cyan itself. */
	private static final int OUTLINE_WIDTH = 4;
	private static final int FEATHER = 4;
	private static final int ARC = 6;
	private static final float STROKE = 2f;

	private final Client client;
	private final ModelOutlineRenderer outlines;
	private final ItemManager itemManager;
	private final Set<Integer> unmodelled = new HashSet<>();

	private final ModelSilhouette silhouette;

	@Inject
	Highlighter(Client client, ModelOutlineRenderer outlines, ItemManager itemManager, ModelSilhouette silhouette)
	{
		this.client = client;
		this.silhouette = silhouette;
		this.outlines = outlines;
		this.itemManager = itemManager;
	}

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
			if (leftClick)
			{
				graphics.setColor(Markers.ONE_CLICK_FILL);
				graphics.fill(silhouette.of(object));
			}
			outlines.drawOutline(object, OUTLINE_WIDTH, Markers.COLOUR, FEATHER);
		}
		catch (NullPointerException e)
		{
			if (unmodelled.add(object.getId()))
			{
				log.warn("No model to highlight object {} at {}", object.getId(), object.getWorldLocation(), e);
			}
		}
	}

	public void npc(Graphics2D graphics, NPC npc, boolean leftClick)
	{
		// An NPC that despawned since the last search has no model, which the outline renderer doesn't check.
		if (npc.getModel() != null && onPlayersFloor(npc.getWorldLocation().getPlane()))
		{
			if (leftClick)
			{
				graphics.setColor(Markers.ONE_CLICK_FILL);
				graphics.fill(silhouette.of(npc));
			}
			outlines.drawOutline(npc, OUTLINE_WIDTH, Markers.COLOUR, FEATHER);
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
			graphics.drawImage(ImageUtil.fillImage(image, Markers.ONE_CLICK_FILL), bounds.x, bounds.y, null);
		}
		graphics.drawImage(itemManager.getItemOutline(itemId, quantity, Markers.COLOUR), bounds.x, bounds.y, null);
	}

	/** An item's outline scaled into {@code bounds}, for a picture larger than the inventory icon (a scroll book entry). */
	public void itemScaled(Graphics2D graphics, int itemId, Rectangle bounds)
	{
		BufferedImage outline = itemManager.getItemOutline(itemId, 1, Markers.COLOUR);
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
		graphics.setColor(Markers.FILL);
		graphics.fill(shape);
		graphics.setColor(Markers.COLOUR);
		graphics.setStroke(new BasicStroke(STROKE));
		graphics.draw(shape);
	}

	/** A spot to walk to with nothing on it to outline. */
	public void tile(Graphics2D graphics, Polygon tile)
	{
		OverlayUtil.renderPolygon(graphics, tile, Markers.COLOUR);
	}

}
