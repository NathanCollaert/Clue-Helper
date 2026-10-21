package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

public class WidgetHighlightOverlay extends Overlay
{
	private static final int ARC = 6;
	private static final float STROKE = 2f;
	private static final Map<StepGuide.Tab, Integer> TAB_CONTENT = Map.of(
		StepGuide.Tab.INVENTORY, InterfaceID.Inventory.ITEMS,
		StepGuide.Tab.EQUIPMENT, InterfaceID.Wornitems.UNIVERSE,
		StepGuide.Tab.MAGIC, InterfaceID.MagicSpellbook.UNIVERSE);
	/** Per tab: its stone in the fixed, resizable classic and resizable modern layouts. */
	private static final Map<StepGuide.Tab, int[]> TAB_STONES = Map.of(
		StepGuide.Tab.INVENTORY, new int[]{InterfaceID.Toplevel.STONE3, InterfaceID.ToplevelOsrsStretch.STONE3, InterfaceID.ToplevelPreEoc.STONE3},
		StepGuide.Tab.EQUIPMENT, new int[]{InterfaceID.Toplevel.STONE4, InterfaceID.ToplevelOsrsStretch.STONE4, InterfaceID.ToplevelPreEoc.STONE4},
		StepGuide.Tab.MAGIC, new int[]{InterfaceID.Toplevel.STONE6, InterfaceID.ToplevelOsrsStretch.STONE6, InterfaceID.ToplevelPreEoc.STONE6});

	private final Client client;
	private final StepGuide guide;
	private final ClueHelperConfig config;

	@Inject
	WidgetHighlightOverlay(Client client, StepGuide guide, ClueHelperConfig config)
	{
		this.client = client;
		this.guide = guide;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (config.highlightSpells())
		{
			outline(graphics, guide.getHighlightSpellWidget());
		}
		if (config.highlightMenus())
		{
			outline(graphics, guide.getHighlightMenuWidget());
		}
		StepGuide.Tab tab = guide.getHighlightTab();
		boolean wanted = tab == StepGuide.Tab.MAGIC ? config.highlightSpells() : config.highlightItems();
		if (tab != StepGuide.Tab.NONE && wanted && !shown(TAB_CONTENT.get(tab)))
		{
			outline(graphics, tabStone(TAB_STONES.get(tab)));
		}
		return null;
	}

	private boolean shown(int widgetId)
	{
		Widget widget = client.getWidget(widgetId);
		return widget != null && !widget.isHidden();
	}

	private int tabStone(int[] perLayout)
	{
		for (int stone : perLayout)
		{
			if (shown(stone))
			{
				return stone;
			}
		}
		return -1;
	}

	private void outline(Graphics2D graphics, int widgetId)
	{
		Widget widget = widgetId < 0 ? null : client.getWidget(widgetId);
		if (widget == null || widget.isHidden())
		{
			return;
		}
		Rectangle bounds = widget.getBounds();
		graphics.setColor(Markers.FILL);
		graphics.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, ARC, ARC);
		graphics.setColor(Markers.COLOUR);
		graphics.setStroke(new BasicStroke(STROKE));
		graphics.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, ARC, ARC);
	}
}
