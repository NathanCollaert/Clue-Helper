package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/** Outlines the item the current sub-step uses, in the inventory and the equipment tab. */
public class ItemHighlightOverlay extends WidgetItemOverlay
{
	private final StepGuide guide;
	private final ItemManager itemManager;
	private final ClueHelperConfig config;

	@Inject
	ItemHighlightOverlay(StepGuide guide, ItemManager itemManager, ClueHelperConfig config)
	{
		this.guide = guide;
		this.itemManager = itemManager;
		this.config = config;
		showOnInventory();
		showOnEquipment();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem item)
	{
		if (!config.highlightItems() || !guide.getHighlightItems().contains(itemId))
		{
			return;
		}
		Rectangle bounds = item.getCanvasBounds();
		BufferedImage outline = itemManager.getItemOutline(itemId, item.getQuantity(), Markers.COLOUR);
		graphics.drawImage(outline, bounds.x, bounds.y, null);
	}
}
