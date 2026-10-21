package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.MenuLines;
import com.cluehelper.guide.ScrollBook;
import com.cluehelper.guide.StepGuide;
import java.awt.Dimension;
import java.awt.Graphics2D;
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
	private static final Map<StepGuide.Tab, Integer> TAB_CONTENT = Map.of(
		StepGuide.Tab.INVENTORY, InterfaceID.Inventory.ITEMS,
		StepGuide.Tab.EQUIPMENT, InterfaceID.Wornitems.UNIVERSE,
		StepGuide.Tab.MAGIC, InterfaceID.MagicSpellbook.UNIVERSE,
		StepGuide.Tab.EMOTES, InterfaceID.Emote.UNIVERSE,
		StepGuide.Tab.MUSIC, InterfaceID.Music.UNIVERSE);
	/** Per tab: its stone in the fixed, resizable classic and resizable modern layouts. */
	private static final Map<StepGuide.Tab, int[]> TAB_STONES = Map.of(
		StepGuide.Tab.INVENTORY, new int[]{InterfaceID.Toplevel.STONE3, InterfaceID.ToplevelOsrsStretch.STONE3, InterfaceID.ToplevelPreEoc.STONE3},
		StepGuide.Tab.EQUIPMENT, new int[]{InterfaceID.Toplevel.STONE4, InterfaceID.ToplevelOsrsStretch.STONE4, InterfaceID.ToplevelPreEoc.STONE4},
		StepGuide.Tab.MAGIC, new int[]{InterfaceID.Toplevel.STONE6, InterfaceID.ToplevelOsrsStretch.STONE6, InterfaceID.ToplevelPreEoc.STONE6},
		StepGuide.Tab.EMOTES, new int[]{InterfaceID.Toplevel.STONE12, InterfaceID.ToplevelOsrsStretch.STONE12, InterfaceID.ToplevelPreEoc.STONE12},
		StepGuide.Tab.MUSIC, new int[]{InterfaceID.Toplevel.STONE13, InterfaceID.ToplevelOsrsStretch.STONE13, InterfaceID.ToplevelPreEoc.STONE13});

	private final Client client;
	private final StepGuide guide;
	private final ClueHelperConfig config;
	private final MenuLines menuLines;
	private final Highlighter highlighter;

	@Inject
	WidgetHighlightOverlay(Client client, StepGuide guide, ClueHelperConfig config, MenuLines menuLines, Highlighter highlighter)
	{
		this.menuLines = menuLines;
		this.highlighter = highlighter;
		this.client = client;
		this.guide = guide;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (menuLines.showing(guide.getMenuTarget()) || guide.isTravelling())
		{
			return null;
		}
		if (config.highlightSpells())
		{
			outline(graphics, guide.getHighlightSpellWidget());
		}
		if (config.highlightMenus())
		{
			outlineEntry(graphics, guide.getHighlightMenuWidget());
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

	/**
	 * A scroll book entry gets its scroll's outline, from the item's icon scaled to the book's larger model of it, and
	 * MenuLines colours its name and charges.
	 */
	private void outlineEntry(Graphics2D graphics, int widgetId)
	{
		Widget picture = widgetId < 0 ? null : client.getWidget(ScrollBook.picture(widgetId));
		if (picture == null || picture.isHidden() || picture.getItemId() <= 0)
		{
			outline(graphics, widgetId);
			return;
		}
		highlighter.itemScaled(graphics, picture.getItemId(), picture.getBounds());
	}

	private void outline(Graphics2D graphics, int widgetId)
	{
		Widget widget = widgetId < 0 ? null : client.getWidget(widgetId);
		if (widget == null || widget.isHidden())
		{
			return;
		}
		highlighter.box(graphics, widget.getBounds());
	}
}
