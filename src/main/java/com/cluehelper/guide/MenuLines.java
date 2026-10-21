package com.cluehelper.guide;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * Finds the lines a {@link MenuTarget} points at, for the overlay (B16, PLAN §4 item 7). The teleport interfaces are
 * read from their own scripts' arguments before better-teleport-menu rewrites anything (B12): the nexus by struct id
 * (script 2675), the jewellery box by index (1688), 187 lists by line (218). Bounds are read at draw time. Text lines
 * and menu options are coloured, never rewritten or hidden (no {@code setText}, no {@code setHidden}). Client thread.
 */
@Singleton
public class MenuLines
{
	static final int LIST_TITLE = 219;
	static final int LIST_TITLE_ALT = 9142;
	static final int LIST_LINE = 218;
	static final int LIST_LINE_ALT = 9143;
	static final int NEXUS_LINE = 2675;
	static final int BOX_LINE = 1688;
	static final String HOTKEYS = "123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
	private static final int LIST_GROUP = 187;
	private static final int NEXUS_GROUP = 17;
	private static final int BOX_GROUP = 590;
	/** A menu row is 15 px; the title above the first row and the border below the last take the rest. */
	private static final int MENU_ROW = 15;
	private static final int MENU_BOTTOM = 3;
	/** The fairy ring travel log: a component per code from AIP at 12, each followed by its favourite star (1.13.2). */
	private static final int LOG_FIRST_CODE = 12;
	private static final String MAP_TELEPORT = "Teleport";
	private static final String MAP_REGION = "Select";
	/** Markers' cyan: the colour of every Clue Helper highlight. */
	private static final int TEXT_COLOUR = 0x40D0FF;

	private final Client client;
	private final QuetzalIcons quetzalIcons;
	private final FairyDials fairyDials;
	private final Map<String, int[]> listLines = new HashMap<>();
	/** The open 187 list's title, and where each of its lines is drawn (the widget text carries a greyed line's colour). */
	private String listTitle;
	private final List<int[]> rawListLines = new ArrayList<>();
	private boolean listFresh;
	private final Map<Integer, int[]> nexusLines = new HashMap<>();
	private final Map<Integer, int[]> boxLines = new HashMap<>();
	/** Lines coloured for the target, with the colour to put back. */
	private final Map<Widget, Integer> coloured = new IdentityHashMap<>();

	@Inject
	MenuLines(Client client, QuetzalIcons quetzalIcons, FairyDials fairyDials)
	{
		this.client = client;
		this.quetzalIcons = quetzalIcons;
		this.fairyDials = fairyDials;
	}

	/** From the plugin's handler at priority 2: better-teleport-menu works at 1. */
	public void onScriptPreFired(ScriptPreFired event)
	{
		switch (event.getScriptId())
		{
			case LIST_TITLE:
			case LIST_TITLE_ALT:
				listLines.clear();
				rawListLines.clear();
				listTitle = objectTail();
				break;
			case LIST_LINE:
			case LIST_LINE_ALT:
			{
				int[] ints = intTail(6);
				String label = objectTail();
				if (ints != null && label != null)
				{
					listLines.put(MenuLabels.normalise(label), new int[]{ints[0], ints[3], ints[3]});
					rawListLines.add(new int[]{ints[0], ints[3]});
					listFresh = true;
				}
				break;
			}
			case NEXUS_LINE:
			{
				int[] ints = intTail(8);
				if (ints != null)
				{
					nexusLines.put(ints[3], new int[]{ints[7], ints[0]});
				}
				break;
			}
			case BOX_LINE:
			{
				int[] ints = intTail(7);
				if (ints != null)
				{
					boxLines.put(ints[6], new int[]{ints[0], ints[4]});
				}
				break;
			}
			default:
				break;
		}
	}

	/**
	 * Once per list drawn, a tick after its lines: hands over the title and each line's widget text with its colour tags
	 * (B17's planted spirit trees: a tree that can't be used is greyed; the script's own label has no colour).
	 */
	public void takeFreshList(BiConsumer<String, List<String>> reader)
	{
		if (!listFresh)
		{
			return;
		}
		listFresh = false;
		List<String> texts = new ArrayList<>();
		for (int[] at : rawListLines)
		{
			Widget line = child(at);
			if (line != null && line.getText() != null)
			{
				texts.add(line.getText());
			}
		}
		reader.accept(listTitle, texts);
	}

	public void onWidgetClosed(WidgetClosed event)
	{
		coloured.keySet().removeIf(line -> line.getId() >>> 16 == event.getGroupId());
		if (event.getGroupId() == LIST_GROUP)
		{
			listLines.clear();
		}
		else if (event.getGroupId() == NEXUS_GROUP)
		{
			nexusLines.clear();
		}
		else if (event.getGroupId() == BOX_GROUP)
		{
			boxLines.clear();
		}
	}

	/** Interface lines without text (map icons, region shields, the travel log): outlined, clipped to their box. */
	public List<Rectangle> lines(MenuTarget target)
	{
		List<Rectangle> out = new ArrayList<>();
		for (Widget widget : lineWidgets(target))
		{
			Rectangle shown = hasText(widget) ? null : clipped(widget);
			if (shown != null && !shown.isEmpty())
			{
				out.add(shown);
			}
		}
		return out;
	}

	/**
	 * Client tick: colours the target's text lines blue, as Quest Helper does (Nathan, B16), and puts back the colour of
	 * lines no longer wanted. Only the text colour changes, never the text or its visibility.
	 */
	public void colourLines(MenuTarget target, boolean enabled)
	{
		Set<Widget> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
		if (enabled)
		{
			lineWidgets(target).stream().filter(MenuLines::hasText).forEach(wanted::add);
		}
		for (Iterator<Map.Entry<Widget, Integer>> it = coloured.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<Widget, Integer> line = it.next();
			if (!wanted.contains(line.getKey()))
			{
				line.getKey().setTextColor(line.getValue());
				it.remove();
			}
		}
		for (Widget line : wanted)
		{
			coloured.putIfAbsent(line, line.getTextColor());
			if (line.getTextColor() != TEXT_COLOUR)
			{
				line.setTextColor(TEXT_COLOUR);
			}
		}
	}

	/** On {@code MenuOpened}: the target's option (and its sub-menu line) in blue, the rest of the text as the game wrote it. */
	public void colourMenu(MenuTarget target, boolean enabled)
	{
		if (!enabled || target == null || target == MenuTarget.NONE)
		{
			return;
		}
		Menu menu = client.getMenu();
		MenuEntry[] entries = menu.getMenuEntries();
		for (int row : pickRows(entries, target))
		{
			colour(entries[row]);
		}
		String destination = target.destination();
		for (MenuEntry entry : entries)
		{
			Menu sub = entry.getSubMenu();
			if (sub == null || destination == null || !concerns(entry, target))
			{
				continue;
			}
			MenuEntry[] options = sub.getMenuEntries();
			boolean named = false;
			for (MenuEntry option : options)
			{
				if (MenuLabels.matches(option.getOption(), destination))
				{
					colour(option);
					named = true;
				}
			}
			int line = MenuLabels.hotkeyIndex(destination);
			if (!named && line >= 0 && line < options.length)
			{
				colour(options[options.length - 1 - line]);
			}
		}
	}

	private static void colour(MenuEntry entry)
	{
		entry.setOption(ColorUtil.wrapWithColorTag(Text.removeTags(entry.getOption()), new Color(TEXT_COLOUR)));
	}

	private List<Widget> lineWidgets(MenuTarget target)
	{
		List<Widget> out = new ArrayList<>();
		if (target == null || target == MenuTarget.NONE)
		{
			return out;
		}
		switch (target.getLine())
		{
			case NEXUS:
				nexus(target, out);
				break;
			case BOX:
				add(child(boxLines.get(target.getLineId())), out);
				break;
			case FAIRY_CODE:
				fairyCode(target.getLineKey(), out);
				break;
			case MINIGAME:
				minigame(target.getLineName(), out);
				break;
			case QUETZAL:
				quetzal(target.getLineId(), out);
				break;
			default:
				labelled(target, out);
				break;
		}
		for (MenuTarget alternative : target.getAlternatives())
		{
			out.addAll(lineWidgets(alternative));
		}
		return out;
	}

	/** The rows of the open menu the target picks, as screen rectangles (the dev trace). */
	public List<Rectangle> menuRows(MenuTarget target)
	{
		List<Rectangle> out = new ArrayList<>();
		if (target == null || target == MenuTarget.NONE || !client.isMenuOpen())
		{
			return out;
		}
		Menu menu = client.getMenu();
		MenuEntry[] entries = menu.getMenuEntries();
		for (int pick : pickRows(entries, target))
		{
			out.add(row(menu, entries.length, pick));
		}
		return out;
	}

	/** The destination's own option, else the option leading to it (a sub-menu), else one that opens the teleport list. */
	private int[] pickRows(MenuEntry[] entries, MenuTarget target)
	{
		String destination = target.destination();
		int pick = find(entries, target, entry -> isDestination(entry, target, destination));
		if (pick < 0)
		{
			pick = find(entries, target, entry -> leadsToDestination(entry, target, destination));
		}
		if (pick < 0)
		{
			pick = find(entries, target, entry -> MenuLabels.isOpener(entry.getOption()));
		}
		return pick < 0 ? new int[0] : new int[]{pick};
	}

	/** A 187 line exists for the target but another plugin hides it (spirit-tree-map, B12): its hotkey, else 0. */
	public char hiddenListHotkey(MenuTarget target)
	{
		if (target == null || target.getLine() != MenuTarget.Line.LABEL || target.destination() == null)
		{
			return 0;
		}
		for (Map.Entry<String, int[]> line : listLines.entrySet())
		{
			if (MenuLabels.matches(line.getKey(), target.destination()))
			{
				Widget widget = child(line.getValue());
				int index = line.getValue()[2];
				boolean shown = widget != null && !widget.isHidden();
				return shown || index >= HOTKEYS.length() ? 0 : HOTKEYS.charAt(index);
			}
		}
		return 0;
	}

	private void nexus(MenuTarget target, List<Widget> out)
	{
		Widget line = child(nexusLines.get(target.getLineId()));
		if (visible(line))
		{
			add(labelOf(line), out);
			return;
		}
		Widget map = client.getWidget(InterfaceID.TelenexusTeleport.FRAME);
		Widget[] icons = map == null || map.isHidden() ? null : map.getDynamicChildren();
		if (icons == null)
		{
			return;
		}
		Widget region = null;
		for (Widget icon : icons)
		{
			if (!visible(icon) || icon.getActions() == null)
			{
				continue;
			}
			if (hasAction(icon, MAP_TELEPORT) && MenuLabels.matches(icon.getName(), target.getLineName()))
			{
				add(icon, out);
				return;
			}
			if (hasAction(icon, MAP_REGION) && MenuLabels.matches(icon.getName(), target.getLineKey()))
			{
				region = icon;
			}
		}
		add(region, out);
	}

	/** The code in the travel log, and the teleport button once the dials show it; the dials are {@link FairyDials}'. */
	private void fairyCode(String code, List<Widget> out)
	{
		String letters = FairyDials.letters(code);
		if (letters == null)
		{
			return;
		}
		add(client.getWidget(InterfaceID.FairyringsLog.UNIVERSE | (LOG_FIRST_CODE + 2 * FairyDials.logIndex(letters))), out);
		if (fairyDials.isDialled(letters))
		{
			add(client.getWidget(InterfaceID.Fairyrings.CONFIRM), out);
		}
	}

	/** The Grouping tab: the minigame chosen shows the Teleport button; else the dropdown, then its line. */
	private void minigame(String name, List<Widget> out)
	{
		Widget current = client.getWidget(InterfaceID.Grouping.CURRENTGAME);
		if (!visible(current))
		{
			return;
		}
		if (MenuLabels.matches(current.getText(), name))
		{
			add(client.getWidget(InterfaceID.Grouping.TELEPORT), out);
			return;
		}
		Widget contents = client.getWidget(InterfaceID.Grouping.DROPDOWN_CONTENTS);
		Widget[] lines = visible(contents) ? contents.getDynamicChildren() : null;
		if (lines != null)
		{
			for (Widget line : lines)
			{
				if (visible(line) && MenuLabels.matches(line.getText(), name))
				{
					add(line, out);
					return;
				}
			}
		}
		add(client.getWidget(InterfaceID.Grouping.DROPDOWN_TOP), out);
	}

	/**
	 * The site's icon on whichever quetzal map is open (Renu's 874, the whistle's 949): the icon nearest the position the
	 * cache's Quetzal table gives it, never a fixed pixel offset (B17).
	 */
	private void quetzal(int site, List<Widget> out)
	{
		int[] at = quetzalIcons.position(site);
		if (at == null)
		{
			return;
		}
		Widget icons = client.getWidget(InterfaceID.QuetzalMenu.ICONS);
		if (!visible(icons))
		{
			icons = client.getWidget(InterfaceID.QuetzalwhistleMenu.ICONS);
		}
		if (!visible(icons))
		{
			return;
		}
		add(QuetzalIcons.nearest(icons.getDynamicChildren(), at), out);
	}

	/** A 187 list line, else a chatbox option, matching the destination (then any earlier label of the path). */
	private void labelled(MenuTarget target, List<Widget> out)
	{
		String destination = target.destination();
		if (destination == null)
		{
			return;
		}
		for (Map.Entry<String, int[]> line : listLines.entrySet())
		{
			if (MenuLabels.matches(line.getKey(), destination))
			{
				add(child(line.getValue()), out);
				return;
			}
		}
		int index = MenuLabels.hotkeyIndex(destination);
		for (int[] line : listLines.values())
		{
			if (index >= 0 && line[2] == index)
			{
				add(child(line), out);
				return;
			}
		}
		Widget options = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
		Widget[] lines = options == null || options.isHidden() ? null : options.getDynamicChildren();
		if (lines == null)
		{
			return;
		}
		List<String> labels = new ArrayList<>(target.getPath());
		for (int i = labels.size() - 1; i >= 0; i--)
		{
			for (Widget option : lines)
			{
				if (visible(option) && MenuLabels.matches(option.getText(), labels.get(i)))
				{
					add(option, out);
					return;
				}
			}
		}
	}

	private boolean isDestination(MenuEntry entry, MenuTarget target, String destination)
	{
		if (target.getLine() == MenuTarget.Line.FAIRY_CODE && target.getLineKey() != null)
		{
			return MenuLabels.isLastDestination(entry.getOption(), target.getLineKey());
		}
		return destination != null && MenuLabels.matches(entry.getOption(), destination);
	}

	private static boolean leadsToDestination(MenuEntry entry, MenuTarget target, String destination)
	{
		List<String> path = target.getPath();
		for (int i = 0; i < path.size() - 1; i++)
		{
			if (MenuLabels.matches(entry.getOption(), path.get(i)))
			{
				return true;
			}
		}
		Menu sub = entry.getSubMenu();
		if (sub == null || destination == null)
		{
			return false;
		}
		for (MenuEntry option : sub.getMenuEntries())
		{
			if (MenuLabels.matches(option.getOption(), destination))
			{
				return true;
			}
		}
		return false;
	}

	private int find(MenuEntry[] entries, MenuTarget target, Predicate<MenuEntry> wanted)
	{
		for (int i = entries.length - 1; i >= 0; i--)
		{
			if (concerns(entries[i], target) && wanted.test(entries[i]))
			{
				return i;
			}
		}
		return -1;
	}

	/** The entry is an option on the target's item or object. */
	private static boolean concerns(MenuEntry entry, MenuTarget target)
	{
		if (target.getItemIds().contains(entry.getItemId()))
		{
			return true;
		}
		Widget widget = entry.getWidget();
		if (widget != null && target.getItemIds().contains(widget.getItemId()))
		{
			return true;
		}
		if (entry.getItemId() <= 0 && target.getItemNames().contains(MenuLabels.normalise(entry.getTarget())))
		{
			return true;
		}
		MenuAction type = entry.getType();
		boolean objectOption = type == MenuAction.GAME_OBJECT_FIRST_OPTION || type == MenuAction.GAME_OBJECT_SECOND_OPTION
			|| type == MenuAction.GAME_OBJECT_THIRD_OPTION || type == MenuAction.GAME_OBJECT_FOURTH_OPTION
			|| type == MenuAction.GAME_OBJECT_FIFTH_OPTION;
		if (objectOption && target.getObjectIds().contains(entry.getIdentifier()))
		{
			return true;
		}
		NPC npc = entry.getNpc();
		return npc != null && (target.getNpcIds().contains(npc.getId())
			|| npc.getTransformedComposition() != null && target.getNpcIds().contains(npc.getTransformedComposition().getId()));
	}

	/** Entries run bottom-up: the last one is the top row. */
	private static Rectangle row(Menu menu, int count, int index)
	{
		int top = menu.getMenuY() + menu.getMenuHeight() - MENU_BOTTOM - count * MENU_ROW;
		return new Rectangle(menu.getMenuX(), top + (count - 1 - index) * MENU_ROW, menu.getMenuWidth(), MENU_ROW);
	}

	private Widget child(int[] at)
	{
		if (at == null)
		{
			return null;
		}
		Widget parent = client.getWidget(at[0]);
		return parent == null ? null : parent.getChild(at[1]);
	}

	private static boolean visible(Widget widget)
	{
		return widget != null && !widget.isHidden() && widget.getBounds() != null && widget.getBounds().width > 0;
	}

	private static boolean hasAction(Widget widget, String action)
	{
		for (String a : widget.getActions())
		{
			if (action.equalsIgnoreCase(a))
			{
				return true;
			}
		}
		return false;
	}

	private static void add(Widget widget, List<Widget> out)
	{
		if (visible(widget))
		{
			out.add(widget);
		}
	}

	private static boolean hasText(Widget widget)
	{
		return widget.getText() != null && !Text.removeTags(widget.getText()).isBlank();
	}

	/**
	 * Rows past a scrolling list's window keep {@code hidden = false} with bounds below the box (B12): clip them to every
	 * enclosing widget, since the list's own layer is as tall as all its rows and only the scroll window is visible (B17).
	 */
	private static Rectangle clipped(Widget widget)
	{
		Rectangle bounds = widget.getBounds();
		for (Widget parent = widget.getParent(); parent != null && !bounds.isEmpty(); parent = parent.getParent())
		{
			Rectangle outer = parent.getBounds();
			if (outer != null && outer.width > 0)
			{
				bounds = bounds.intersection(outer);
			}
		}
		return bounds;
	}

	/**
	 * A nexus row is a text-less background in the row layer; its label is drawn at the same height in the text layer
	 * (B17, live: {@code ROWS1} and {@code TEXT1}), and the label is what gets coloured. The row itself when no label is
	 * found.
	 */
	private Widget labelOf(Widget row)
	{
		if (hasText(row))
		{
			return row;
		}
		Widget text = client.getWidget(InterfaceID.TelenexusTeleport.TEXT1);
		Widget[] labels = text == null ? null : text.getDynamicChildren();
		int top = row.getRelativeY();
		int bottom = top + row.getHeight();
		for (Widget label : labels == null ? new Widget[0] : labels)
		{
			int middle = label == null ? -1 : label.getRelativeY() + label.getHeight() / 2;
			if (label != null && hasText(label) && middle >= top && middle < bottom)
			{
				return label;
			}
		}
		return row;
	}

	private int[] intTail(int count)
	{
		int size = client.getIntStackSize();
		if (size < count)
		{
			return null;
		}
		int[] tail = new int[count];
		System.arraycopy(client.getIntStack(), size - count, tail, 0, count);
		return tail;
	}

	private String objectTail()
	{
		int size = client.getObjectStackSize();
		Object last = size < 1 ? null : client.getObjectStack()[size - 1];
		return last instanceof String ? (String) last : null;
	}
}
