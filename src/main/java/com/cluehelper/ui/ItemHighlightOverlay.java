package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.*;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.*;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

public class ItemHighlightOverlay extends WidgetItemOverlay
{
	@Inject
	private Client client;
	@Inject
	private StepGuide guide;
	@Inject
	private ClueHelperConfig config;
	@Inject
	private Highlighter highlighter;
	@Inject
	private LeftClicks leftClicks;

	ItemHighlightOverlay()
	{
		showOnInventory();
		showOnEquipment();
		showOnBank();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem item)
	{
		boolean inBank = item.getWidget() != null && WidgetUtil.componentToInterface(item.getWidget().getId()) == InterfaceID.BANKMAIN;
		if (!config.highlightItems() || !(inBank ? guide.getHighlights().getBankItems() : guide.getHighlights().getItems()).contains(itemId)
			|| !inBank && (guide.isTravelling() || isUsedAfterTheBank()))
		{
			return;
		}
		boolean leftClick = inBank ? leftClicks.withdraws(itemId) : guide.getHighlights().getFillItems().contains(itemId);
		highlighter.item(graphics, itemId, item.getQuantity(), item.getCanvasBounds(), leftClick);
	}

	/**
	 * With the bank open, a carried item is marked only to deposit it: a teleport marked there reads as one to put in the
	 * bank, though it is used once the bank is closed.
	 */
	private boolean isUsedAfterTheBank()
	{
		SubStep current = guide.getCurrent();
		return Widgets.shown(client, InterfaceID.Bankmain.UNIVERSE) && (current == null || !current.isDeposit());
	}
}
