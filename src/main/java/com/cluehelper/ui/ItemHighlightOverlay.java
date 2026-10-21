package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.util.ImageUtil;

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
		showOnBank();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem item)
	{
		boolean inBank = item.getWidget() != null && WidgetUtil.componentToInterface(item.getWidget().getId()) == InterfaceID.BANKMAIN;
		if (!config.highlightItems() || !(inBank ? guide.getBankHighlightItems() : guide.getHighlightItems()).contains(itemId))
		{
			return;
		}
		Rectangle bounds = item.getCanvasBounds();
		if (!inBank && guide.getFillItems().contains(itemId))
		{
			BufferedImage image = itemManager.getImage(itemId, item.getQuantity(), false);
			graphics.drawImage(ImageUtil.fillImage(image, Markers.ONE_CLICK_FILL), bounds.x, bounds.y, null);
		}
		BufferedImage outline = itemManager.getItemOutline(itemId, item.getQuantity(), Markers.COLOUR);
		graphics.drawImage(outline, bounds.x, bounds.y, null);
	}
}
