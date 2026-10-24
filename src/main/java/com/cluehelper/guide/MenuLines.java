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
import java.util.regex.Pattern;
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
 * Teleport interfaces are read from their scripts' arguments, before better-teleport-menu rewrites them. Lines and menu
 * options are only recoloured, never rewritten or hidden. Client thread only.
 */
@Singleton
public class MenuLines
{
	private static final int LIST_TITLE = 219;
	private static final int LIST_TITLE_ALT = 9142;
	private static final int LIST_LINE = 218;
	private static final int LIST_LINE_ALT = 9143;
	private static final int NEXUS_LINE = 2675;
	private static final int BOX_LINE = 1688;
	static final String HOTKEYS = "123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
	private static final int LIST_GROUP = 187;
	private static final Pattern MINIGAME_PLACE_NUMBER = Pattern.compile("^\\d+\\. ");
	/** A minigame card's text is its name, a break, then its places. */
	private static final String LINE_BREAK = "<br>";
	private static final int NEXUS_GROUP = 17;
	private static final int BOX_GROUP = 590;
	private static final int LOG_FIRST_CODE = 12;
	/** Each code in the travel log is followed by its favourite star. */
	private static final int LOG_COMPONENTS_PER_CODE = 2;
	private static final String MAP_TELEPORT = "Teleport";
	private static final String MAP_REGION = "Select";
	/** Same cyan as {@code Markers.COLOUR}, the colour of every Clue Helper highlight. */
	private static final int TEXT_COLOUR = 0x40D0FF;

	/** A hotkey as the info box names it: upper case, in the colour of the menu line it presses. */
	static String keyText(char key)
	{
		return menuColoured(String.valueOf(Character.toUpperCase(key)));
	}

	/** What to press or dial, in the colour of the menu lines; the info box closes the colour. */
	static String menuColoured(String text)
	{
		return String.format("<col=%06x>%s</col>", TEXT_COLOUR, text);
	}

	private final Client client;
	private final QuetzalIcons quetzalIcons;
	private final FairyDials fairyDials;
	private final Map<String, int[]> listLines = new HashMap<>();
	private String listTitle;
	private final List<int[]> rawListLines = new ArrayList<>();
	private boolean listFresh;
	private final Map<Integer, int[]> nexusLines = new HashMap<>();
	private final Map<Integer, int[]> boxLines = new HashMap<>();
	private final Map<Widget, Integer> originalTextColours = new IdentityHashMap<>();

	@Inject
	MenuLines(Client client, QuetzalIcons quetzalIcons, FairyDials fairyDials)
	{
		this.client = client;
		this.quetzalIcons = quetzalIcons;
		this.fairyDials = fairyDials;
	}

	/** Call from a handler at priority 2, ahead of better-teleport-menu's at 1. */
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
	 * Once per list, a tick after its lines are drawn. Hands over the widget texts rather than the script's labels: only
	 * they carry the grey of a planted spirit tree that can't be used.
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
		originalTextColours.keySet().removeIf(line -> line.getId() >>> 16 == event.getGroupId());
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

	/** Whether any of the target's lines is on screen, coloured or outlined: the menu is open at the teleport's line. */
	public boolean showing(MenuTarget target)
	{
		return !lineWidgets(target).isEmpty();
	}

	/** Only the target's lines without text (map icons, region shields, travel log codes); text lines are coloured. */
	public List<Rectangle> lines(MenuTarget target)
	{
		List<Rectangle> out = new ArrayList<>();
		for (Widget widget : lineWidgets(target))
		{
			Rectangle shown = hasTextInside(widget) ? null : clipped(widget);
			if (shown != null && !shown.isEmpty())
			{
				out.add(shown);
			}
		}
		return out;
	}

	/**
	 * Call every client tick: it also puts back the colour of lines no longer wanted.
	 *
	 * @param entryWidget a scroll book entry whose name and charges are coloured too, or -1
	 */
	public void colourLines(MenuTarget target, int entryWidget, boolean enabled)
	{
		Set<Widget> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
		if (enabled)
		{
			lineWidgets(target).stream().flatMap(line -> descendants(line).stream()).filter(MenuLines::hasText).forEach(wanted::add);
			if (entryWidget != ScrollBook.NO_WIDGET)
			{
				for (int text : ScrollBook.texts(entryWidget))
				{
					Widget line = client.getWidget(text);
					if (visible(line) && hasText(line))
					{
						wanted.add(line);
					}
				}
			}
		}
		for (Iterator<Map.Entry<Widget, Integer>> it = originalTextColours.entrySet().iterator(); it.hasNext(); )
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
			originalTextColours.putIfAbsent(line, line.getTextColor());
			if (line.getTextColor() != TEXT_COLOUR)
			{
				line.setTextColor(TEXT_COLOUR);
			}
		}
	}

	/** Call on {@code MenuOpened}. */
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
			if (sub == null || destination == null || !isOptionOnTarget(entry, target))
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
		Found found = new Found();
		find(target, found);
		return found.widgets;
	}

	/** The key the charter's destination list gives the target's port, or 0 while the list isn't open. */
	public char charterKey(MenuTarget target)
	{
		String destination = target == null ? null : target.destination();
		Widget[] rows = dynamicChildren(InterfaceID.CharteringMenuSide.LIST_CONTENT);
		for (int i = 0; destination != null && i + 1 < rows.length; i++)
		{
			String text = rows[i + 1] == null ? null : Text.removeTags(rows[i + 1].getText()).trim();
			if (visible(rows[i]) && namesPort(rows[i], destination) && text != null && !text.isEmpty())
			{
				return text.charAt(0);
			}
		}
		return 0;
	}

	/** The charter map's marker and the destination list's line both carry the port's name as their action. */
	private boolean charter(String destination, Found out)
	{
		boolean found = false;
		for (Widget marker : dynamicChildren(InterfaceID.SailingMenu.CONTENT))
		{
			if (visible(marker) && namesPort(marker, destination))
			{
				out.add(marker);
				found = true;
			}
		}
		Widget[] rows = dynamicChildren(InterfaceID.CharteringMenuSide.LIST_CONTENT);
		for (int i = 0; i + 1 < rows.length; i++)
		{
			if (visible(rows[i]) && namesPort(rows[i], destination))
			{
				out.add(rows[i + 1]);
				found = true;
			}
		}
		return found;
	}

	private Widget[] dynamicChildren(int component)
	{
		Widget parent = client.getWidget(component);
		Widget[] children = parent == null || parent.isHidden() ? null : parent.getDynamicChildren();
		return children == null ? new Widget[0] : children;
	}

	private static boolean namesPort(Widget widget, String label)
	{
		for (String action : widget.getActions() == null ? new String[0] : widget.getActions())
		{
			if (action != null && MenuLabels.matches(action, label))
			{
				return true;
			}
		}
		return false;
	}

	private void find(MenuTarget target, Found out)
	{
		if (target == null || target == MenuTarget.NONE)
		{
			return;
		}
		switch (target.getLine())
		{
			case NEXUS:
				nexus(target, out);
				break;
			case BOX:
				out.add(child(boxLines.get(target.getLineId())));
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
			find(alternative, out);
		}
	}

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

	private void nexus(MenuTarget target, Found out)
	{
		int[] at = nexusLines.get(target.getLineId());
		Widget line = child(at);
		if (visible(line))
		{
			out.add(labelOf(line));
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
				out.add(icon);
				return;
			}
			if (hasAction(icon, MAP_REGION) && MenuLabels.matches(icon.getName(), target.getLineKey()))
			{
				region = icon;
			}
		}
		out.add(region);
	}

	private void fairyCode(String code, Found out)
	{
		String letters = FairyDials.letters(code);
		if (letters == null)
		{
			return;
		}
		int logComponent = LOG_FIRST_CODE + LOG_COMPONENTS_PER_CODE * FairyDials.logIndex(letters);
		out.add(client.getWidget(InterfaceID.FairyringsLog.UNIVERSE | logComponent));
		if (fairyDials.isDialled(letters))
		{
			out.add(client.getWidget(InterfaceID.Fairyrings.CONFIRM));
		}
	}

	private void minigame(String name, Found out)
	{
		if (visible(client.getWidget(InterfaceID.Minigames.MINIGAMES)))
		{
			minigameCard(name, out);
			return;
		}
		if (minigamePlace(name, out))
		{
			return;
		}
		Widget current = client.getWidget(InterfaceID.Grouping.CURRENTGAME);
		if (!visible(current))
		{
			return;
		}
		if (MenuLabels.matches(current.getText(), name))
		{
			out.add(client.getWidget(InterfaceID.Grouping.TELEPORT));
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
					out.add(line);
					return;
				}
			}
		}
		out.add(client.getWidget(InterfaceID.Grouping.DROPDOWN_TOP));
	}

	/**
	 * The Minigame Teleport spell opens a grid of cards, one per minigame, each with the game's name and its places;
	 * the card whose name is the row's game is outlined. A row like "Rat Pits: 4. Port Sarim" names the game first.
	 */
	private void minigameCard(String name, Found out)
	{
		String game = name.split(": ")[0];
		for (int card = InterfaceID.Minigames.MINIGAME_21; card <= InterfaceID.Minigames.MINIGAME_1; card++)
		{
			Widget widget = client.getWidget(card);
			if (visible(widget) && hasTextMatching(widget, game))
			{
				out.add(widget);
				return;
			}
		}
	}

	/** A game with several places asks which one next, e.g. "Port Sarim (wily cats)" for "Rat Pits: 4. Port Sarim". */
	private boolean minigamePlace(String name, Found out)
	{
		String[] parts = name.split(": ", 2);
		Widget options = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
		Widget[] lines = parts.length < 2 || !visible(options) ? null : options.getDynamicChildren();
		if (lines == null)
		{
			return false;
		}
		String place = MenuLabels.normalise(MINIGAME_PLACE_NUMBER.matcher(parts[1]).replaceFirst(""));
		for (Widget line : lines)
		{
			if (visible(line) && MenuLabels.normalise(line.getText()).startsWith(place))
			{
				out.add(line);
				return true;
			}
		}
		return false;
	}

	/** The widget and everything under it. */
	public static List<Widget> descendants(Widget widget)
	{
		List<Widget> out = new ArrayList<>();
		if (widget == null)
		{
			return out;
		}
		out.add(widget);
		for (Widget[] children : new Widget[][]{widget.getStaticChildren(), widget.getDynamicChildren(), widget.getNestedChildren()})
		{
			for (int i = 0; children != null && i < children.length; i++)
			{
				out.addAll(descendants(children[i]));
			}
		}
		return out;
	}

	private static boolean hasTextMatching(Widget widget, String label)
	{
		for (Widget part : descendants(widget))
		{
			String text = part.getText();
			if (text != null && MenuLabels.matches(text.split(LINE_BREAK, 2)[0], label))
			{
				return true;
			}
		}
		return false;
	}

	private void quetzal(int quetzalTableId, Found out)
	{
		int[] at = quetzalIcons.position(quetzalTableId);
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
		out.add(QuetzalIcons.nearest(icons.getDynamicChildren(), at));
	}

	private void labelled(MenuTarget target, Found out)
	{
		String destination = target.destination();
		if (destination == null || charter(destination, out))
		{
			return;
		}
		for (Map.Entry<String, int[]> line : listLines.entrySet())
		{
			if (MenuLabels.matches(line.getKey(), destination))
			{
				Widget listLine = child(line.getValue());
				out.add(visible(listLine) ? listLine : drawnOverList(line.getValue(), destination));
				return;
			}
		}
		int index = MenuLabels.hotkeyIndex(destination);
		for (int[] line : listLines.values())
		{
			if (index >= 0 && line[2] == index)
			{
				out.add(child(line));
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
					out.add(option);
					return;
				}
			}
		}
	}

	/**
	 * Another plugin's map drawn over the hidden list (spirit-tree-map): its button for the destination, a widget in the
	 * list's interface named after it, with an action.
	 */
	private Widget drawnOverList(int[] listLine, String destination)
	{
		for (Widget widget : descendants(client.getWidget(listLine[0] >>> 16, 0)))
		{
			if (visible(widget) && widget.getActions() != null && MenuLabels.matches(widget.getName(), destination))
			{
				return widget;
			}
		}
		return null;
	}

	private boolean isDestination(MenuEntry entry, MenuTarget target, String destination)
	{
		if (target.getLine() == MenuTarget.Line.FAIRY_CODE && target.getLineKey() != null)
		{
			return MenuLabels.isRingDestination(entry.getOption(), target.getLineKey());
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
			if (isOptionOnTarget(entries[i], target) && wanted.test(entries[i]))
			{
				return i;
			}
		}
		return -1;
	}

	private static boolean isOptionOnTarget(MenuEntry entry, MenuTarget target)
	{
		if (target.getItemIds().contains(entry.getItemId()))
		{
			return true;
		}
		Widget widget = entry.getWidget();
		if (widget != null && (target.getItemIds().contains(widget.getItemId()) || target.getWidgetIds().contains(widget.getId())))
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

	/** The target's lines. */
	private static final class Found
	{
		final List<Widget> widgets = new ArrayList<>();

		void add(Widget widget)
		{
			if (visible(widget))
			{
				widgets.add(widget);
			}
		}
	}

	private static boolean hasText(Widget widget)
	{
		return widget.getText() != null && !Text.removeTags(widget.getText()).isBlank();
	}

	/** A nexus row is a container whose text sits in its children: the text is recoloured, so no box is drawn. */
	private static boolean hasTextInside(Widget widget)
	{
		return descendants(widget).stream().anyMatch(MenuLines::hasText);
	}

	/**
	 * Rows scrolled out of view keep {@code hidden = false} and their bounds, and the list's layer is as tall as all its
	 * rows, so only clipping to every ancestor leaves the visible part.
	 */
	private static Rectangle clipped(Widget widget)
	{
		return widget.getBounds().intersection(insideParents(widget));
	}

	private static Rectangle insideParents(Widget widget)
	{
		Rectangle area = new Rectangle(Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MAX_VALUE, Integer.MAX_VALUE);
		for (Widget parent = widget.getParent(); parent != null && !area.isEmpty(); parent = parent.getParent())
		{
			Rectangle outer = parent.getBounds();
			if (outer != null && outer.width > 0)
			{
				area = area.intersection(outer);
			}
		}
		return area;
	}

	/** A nexus row ({@code ROWS1}) has no text; its label sits at the same height in the {@code TEXT1} layer. */
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
