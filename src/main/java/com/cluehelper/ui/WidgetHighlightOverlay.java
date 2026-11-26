package com.cluehelper.ui;

import com.cluehelper.*;
import com.cluehelper.guide.*;
import com.cluehelper.link.ClueLink;
import com.cluehelper.step.*;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.*;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;
import net.runelite.client.ui.overlay.*;
import static java.util.stream.Collectors.*;
import com.cluehelper.guide.StepGuide.Tab;

public class WidgetHighlightOverlay extends Overlay
{
	private static final Map<Tab, Integer> TAB_CONTENT = Tables.byKey("tab content", Tab::valueOf, row -> row.id(0));
	private static final int[] PRAYER_STONES = {InterfaceID.Toplevel.STONE5, InterfaceID.ToplevelOsrsStretch.STONE5,
		InterfaceID.ToplevelPreEoc.STONE5};
	/** Per tab: its stone in the fixed, resizable classic and resizable modern layouts. */
	private static final Map<Tab, int[]> TAB_STONES = Tables.byKey("tab stones", Tab::valueOf, Tables.Row::ids);

	@Inject
	Client client;
	@Inject
	StepGuide guide;
	@Inject
	ClueHelperConfig config;
	@Inject
	MenuLines menuLines;
	@Inject
	Highlighter highlighter;
	@Inject
	ClueLink clueLink;
	@Inject
	PrayerFilters prayerFilters;

	WidgetHighlightOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		prayerFilters.rememberFor(clueLink.getCurrentStep());
		if (menuLines.showing(guide.getHighlights().getMenuTarget()) || guide.isTravelling())
		{
			return null;
		}
		if (config.highlightSpells())
		{
			outlineSpell(graphics, guide.getHighlights());
		}
		guide.getHighlightChoice().forEach(widget -> highlighter.box(graphics, widget.getBounds()));
		if (config.highlightMenus())
		{
			outlineEntry(graphics, guide.getHighlights().getScroll(), guide.getHighlights().isScrollLeftClick());
		}
		if (config.highlightItems())
		{
			outlinePrayer(graphics);
		}
		Tab tab = guide.getHighlights().getTab();
		boolean wanted = tab == Tab.MAGIC ? config.highlightSpells() : config.highlightItems();
		if (tab != Tab.NONE && wanted && !Widgets.shown(client, TAB_CONTENT.get(tab)))
		{
			outline(graphics, tabStone(TAB_STONES.get(tab)));
		}
		return null;
	}

	/**
	 * Sherlock's prayer task: the prayer to turn on while the task is left, and to turn off again while it is on; then the
	 * filters changed to find it, set back.
	 */
	private void outlinePrayer(Graphics2D graphics)
	{
		Step step = clueLink.getCurrentStep();
		Prayer prayer = step == null ? null : step.getData().getPrayer();
		if (prayer == null)
		{
			return;
		}
		if (Stages.taskLeft(step) || client.isPrayerActive(prayer))
		{
			outlinePrayerButton(graphics, prayer);
			return;
		}
		outlineFiltersToRestore(graphics);
	}

	private void outlinePrayerButton(Graphics2D graphics, Prayer prayer)
	{
		if (!Widgets.shown(client, InterfaceID.Prayerbook.UNIVERSE))
		{
			outline(graphics, tabStone(PRAYER_STONES));
			return;
		}
		if (Widgets.shown(client, InterfaceID.Prayerbook.FILTERMENU))
		{
			outlineToggles(graphics, prayerFilters.hidingTiers());
			return;
		}
		Widget widget = named(client.getWidget(InterfaceID.Prayerbook.UNIVERSE), prayer.name());
		if (widget == null || widget.isHidden())
		{
			outline(graphics, InterfaceID.Prayerbook.FILTERBUTTON);
			return;
		}
		highlighter.box(graphics, widget.getBounds());
	}

	private void outlineFiltersToRestore(Graphics2D graphics)
	{
		List<PrayerFilters.Filter> changed = prayerFilters.changed();
		boolean menuOpen = Widgets.shown(client, InterfaceID.Prayerbook.FILTERMENU);
		if (changed.isEmpty() && !menuOpen)
		{
			return;
		}
		if (!Widgets.shown(client, InterfaceID.Prayerbook.UNIVERSE))
		{
			outline(graphics, tabStone(PRAYER_STONES));
			return;
		}
		if (!menuOpen)
		{
			outline(graphics, InterfaceID.Prayerbook.FILTERBUTTON);
			return;
		}
		outlineToggles(graphics, changed);
	}

	/** The filters' lines in the open filter menu, found by their text; with none left, the Filters button to close it. */
	private void outlineToggles(Graphics2D graphics, List<PrayerFilters.Filter> filters)
	{
		List<Widget> toggles = Widgets.descendants(client.getWidget(InterfaceID.Prayerbook.FILTERMENU)).stream()
			.filter(widget -> filters.stream().anyMatch(filter -> plainText(widget).contains(filter.getPhrase())))
			.collect(toList());
		if (toggles.isEmpty())
		{
			outline(graphics, InterfaceID.Prayerbook.FILTERBUTTON);
			return;
		}
		toggles.forEach(widget -> highlighter.box(graphics, widget.getBounds()));
	}

	/** Lower case without tags; "" for a widget without text. */
	private static String plainText(Widget widget)
	{
		return widget.getText() == null ? "" : Text.removeTags(widget.getText()).toLowerCase();
	}

	/** The prayer's own button, found by its name: the book's component order isn't the prayers' order. */
	private static Widget named(Widget parent, String name)
	{
		return Widgets.descendants(parent).stream()
			.filter(widget -> widget.getName() != null && Text.removeTags(widget.getName()).equalsIgnoreCase(name))
			.findFirst().orElse(null);
	}

	private int tabStone(int[] perLayout)
	{
		return Arrays.stream(perLayout).filter(stone -> Widgets.shown(client, stone)).findFirst().orElse(-1);
	}

	/**
	 * A scroll book entry gets its scroll's outline, from the item's icon scaled to the book's larger model of it, and
	 * MenuLines colours its name and charges.
	 */
	private void outlineEntry(Graphics2D graphics, ScrollBook.Scroll scroll, boolean leftClick)
	{
		if (scroll == null)
		{
			return;
		}
		Widget picture = client.getWidget(scroll.getPicture());
		if (picture == null || picture.isHidden() || picture.getItemId() <= 0)
		{
			outline(graphics, scroll.getEntry());
			return;
		}
		highlighter.itemScaled(graphics, picture.getItemId(), picture.getBounds(), leftClick);
	}

	private void outlineSpell(Graphics2D graphics, Highlights shown)
	{
		Widget spell = shown.getSpellWidget() < 0 ? null : client.getWidget(shown.getSpellWidget());
		if (spell != null && !spell.isHidden())
		{
			highlighter.spell(graphics, spell.getBounds(), !shown.isSpellRightClick());
		}
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
