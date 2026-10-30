package com.cluehelper.guide;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.*;
import java.util.regex.Pattern;
import java.util.function.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.*;

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
	/** The Pendant of Ates' map has one button per place, by the place its catalog line names. */
	private static final Map<String, Integer> ATES_BUTTONS = Map.of(
		"The Darkfrost", InterfaceID.PendantOfAtes.TELEPORT_DARKFROST,
		"Twilight Temple", InterfaceID.PendantOfAtes.TELEPORT_TWILIGHT,
		"Ralos' Rise", InterfaceID.PendantOfAtes.TELEPORT_RALOS,
		"North Aldarin", InterfaceID.PendantOfAtes.TELEPORT_ALDARIN,
		"Kastori", InterfaceID.PendantOfAtes.TELEPORT_TLATI,
		"Nemus Retreat", InterfaceID.PendantOfAtes.TELEPORT_AUBURN);
	/** Each code in the travel log is followed by its favourite star. */
	private static final int LOG_COMPONENTS_PER_CODE = 2;
	private static final String MAP_TELEPORT = "Teleport";
	private static final String MAP_REGION = "Select";
	private static final String OPEN_OPTION = "Open";
	private static final String CLOSE_OPTION = "Close";
	/** Same cyan as {@code Highlighter.COLOUR}, the colour of every Clue Helper highlight. */
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

	@Inject
	private Client client;
	@Inject
	private QuetzalIcons quetzalIcons;
	@Inject
	private FairyDials fairyDials;
	@Inject
	private SceneObjects sceneObjects;
	private final Map<String, int[]> listLines = new HashMap<>();
	private String listTitle;
	private final List<int[]> rawListLines = new ArrayList<>();
	private boolean listFresh;
	private final Map<Integer, int[]> nexusLines = new HashMap<>();
	private final Map<Integer, int[]> boxLines = new HashMap<>();
	private final Map<Widget, Integer> originalTextColours = new IdentityHashMap<>();

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
		return !lineWidgets(target).widgets.isEmpty();
	}

	/** Only the target's lines without text (map icons, region shields, travel log codes); text lines are coloured. */
	public List<Rectangle> lines(MenuTarget target)
	{
		List<Rectangle> out = new ArrayList<>();
		Found found = lineWidgets(target);
		for (Widget widget : found.widgets)
		{
			boolean boxed = found.mapButtons.contains(widget) || !Widgets.hasTextInside(widget);
			Rectangle shown = boxed ? Widgets.clipped(widget) : null;
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
			Found found = lineWidgets(target);
			found.widgets.stream().filter(line -> !found.mapButtons.contains(line)).flatMap(line -> Widgets.descendants(line).stream())
				.filter(Widgets::hasText).forEach(wanted::add);
			if (entryWidget != ScrollBook.NO_WIDGET)
			{
				for (int text : ScrollBook.texts(entryWidget))
				{
					Widget line = client.getWidget(text);
					if (Widgets.visible(line) && Widgets.hasText(line))
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

	private Found lineWidgets(MenuTarget target)
	{
		Found found = new Found();
		find(target, found);
		return found;
	}

	/** The key the charter's destination list gives the target's port, or 0 while the list isn't open. */
	public char charterKey(MenuTarget target)
	{
		String destination = target == null ? null : target.destination();
		Widget[] rows = dynamicChildren(InterfaceID.CharteringMenuSide.LIST_CONTENT);
		for (int i = 0; destination != null && i + 1 < rows.length; i++)
		{
			String text = rows[i + 1] == null ? null : Text.removeTags(rows[i + 1].getText()).trim();
			if (Widgets.visible(rows[i]) && namesPlace(rows[i], destination) && text != null && !text.isEmpty())
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
			if (Widgets.visible(marker) && namesPlace(marker, destination))
			{
				out.add(marker);
				found = true;
			}
		}
		Widget[] rows = dynamicChildren(InterfaceID.CharteringMenuSide.LIST_CONTENT);
		for (int i = 0; i + 1 < rows.length; i++)
		{
			if (Widgets.visible(rows[i]) && namesPlace(rows[i], destination))
			{
				out.add(rows[i + 1]);
				found = true;
			}
		}
		return found;
	}

	/** Each mushroom on the mushtree's map is a Travel button named after its place, written with non-breaking spaces. */
	private boolean mushtree(String destination, Found out)
	{
		boolean found = false;
		for (Widget button : dynamicChildren(InterfaceID.FossilMushtrees.UNIVERSE))
		{
			String place = button == null || button.getName() == null ? null : button.getName().replace(' ', ' ');
			if (Widgets.visible(button) && MenuLabels.matches(place, destination))
			{
				out.addMapButton(button);
				found = true;
			}
		}
		return found;
	}

	/** Each place on the glider map is a button with the place's name as its action. */
	private boolean glider(String destination, Found out)
	{
		boolean found = false;
		for (Widget button : Widgets.descendants(client.getWidget(InterfaceID.Glidermap.UNIVERSE)))
		{
			if (Widgets.visible(button) && namesPlace(button, destination))
			{
				out.addMapButton(button);
				found = true;
			}
		}
		return found;
	}

	private boolean atesMap(String destination, Found out)
	{
		for (Map.Entry<String, Integer> place : ATES_BUTTONS.entrySet())
		{
			Widget button = client.getWidget(place.getValue());
			if (MenuLabels.matches(place.getKey(), destination) && Widgets.visible(button))
			{
				out.addMapButton(button);
				return true;
			}
		}
		return false;
	}

	private Widget[] dynamicChildren(int component)
	{
		Widget parent = client.getWidget(component);
		Widget[] children = parent == null || parent.isHidden() ? null : parent.getDynamicChildren();
		return children == null ? new Widget[0] : children;
	}

	private static boolean namesPlace(Widget widget, String label)
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
		int pick = find(entries, target, entry -> isExactly(entry, destination));
		if (pick < 0)
		{
			pick = find(entries, target, entry -> isDestination(entry, target, destination));
		}
		if (pick < 0)
		{
			pick = find(entries, target, entry -> leadsToDestination(entry, target, destination));
		}
		if (pick < 0)
		{
			pick = find(entries, target, entry -> MenuLabels.isOpener(entry.getOption()));
		}
		if (pick < 0)
		{
			pick = find(entries, target, entry -> goesThroughOpened(entry, destination));
		}
		return pick < 0 ? new int[0] : new int[]{pick};
	}

	private void nexus(MenuTarget target, Found out)
	{
		int[] at = nexusLines.get(target.getLineId());
		Widget line = child(at);
		if (Widgets.visible(line))
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
			if (!Widgets.visible(icon) || icon.getActions() == null)
			{
				continue;
			}
			if (Widgets.hasAction(icon.getActions(), MAP_TELEPORT) && MenuLabels.matches(icon.getName(), target.getLineName()))
			{
				out.add(icon);
				return;
			}
			if (Widgets.hasAction(icon.getActions(), MAP_REGION) && MenuLabels.matches(icon.getName(), target.getLineKey()))
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
		if (Widgets.visible(client.getWidget(InterfaceID.Minigames.MINIGAMES)))
		{
			minigameCard(name, out);
			return;
		}
		if (minigamePlace(name, out))
		{
			return;
		}
		Widget current = client.getWidget(InterfaceID.Grouping.CURRENTGAME);
		if (!Widgets.visible(current))
		{
			return;
		}
		if (MenuLabels.matches(current.getText(), name))
		{
			out.add(client.getWidget(InterfaceID.Grouping.TELEPORT));
			return;
		}
		Widget contents = client.getWidget(InterfaceID.Grouping.DROPDOWN_CONTENTS);
		Widget[] lines = Widgets.visible(contents) ? contents.getDynamicChildren() : null;
		if (lines != null)
		{
			for (Widget line : lines)
			{
				if (Widgets.visible(line) && MenuLabels.matches(line.getText(), name))
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
	 * the card whose name is the row's game is boxed whole, icon and all. A row like "Rat Pits: 4. Port Sarim" names the
	 * game first.
	 */
	private void minigameCard(String name, Found out)
	{
		String game = name.split(": ")[0];
		for (int card = InterfaceID.Minigames.MINIGAME_21; card <= InterfaceID.Minigames.MINIGAME_1; card++)
		{
			Widget widget = client.getWidget(card);
			if (Widgets.visible(widget) && hasTextMatching(widget, game))
			{
				out.addMapButton(widget);
				return;
			}
		}
	}

	/** A game with several places asks which one next, e.g. "Port Sarim (wily cats)" for "Rat Pits: 4. Port Sarim". */
	private boolean minigamePlace(String name, Found out)
	{
		String[] parts = name.split(": ", 2);
		Widget options = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
		Widget[] lines = parts.length < 2 || !Widgets.visible(options) ? null : options.getDynamicChildren();
		if (lines == null)
		{
			return false;
		}
		String place = MenuLabels.normalise(MINIGAME_PLACE_NUMBER.matcher(parts[1]).replaceFirst(""));
		for (Widget line : lines)
		{
			if (Widgets.visible(line) && MenuLabels.normalise(line.getText()).startsWith(place))
			{
				out.add(line);
				return true;
			}
		}
		return false;
	}

	private static boolean hasTextMatching(Widget widget, String label)
	{
		for (Widget part : Widgets.descendants(widget))
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
		if (!Widgets.visible(icons))
		{
			icons = client.getWidget(InterfaceID.QuetzalwhistleMenu.ICONS);
		}
		if (!Widgets.visible(icons))
		{
			return;
		}
		out.add(QuetzalIcons.nearest(icons.getDynamicChildren(), at));
	}

	private void labelled(MenuTarget target, Found out)
	{
		String destination = target.destination();
		if (destination == null || charter(destination, out) || glider(destination, out) || mushtree(destination, out)
			|| atesMap(destination, out))
		{
			return;
		}
		for (Map.Entry<String, int[]> line : listLines.entrySet())
		{
			if (MenuLabels.matches(line.getKey(), destination))
			{
				Widget listLine = child(line.getValue());
				out.add(Widgets.visible(listLine) ? listLine : drawnOverList(line.getValue(), destination));
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
				if (Widgets.visible(option) && MenuLabels.matches(option.getText(), labels.get(i)))
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
		for (Widget widget : Widgets.descendants(client.getWidget(listLine[0] >>> 16, 0)))
		{
			if (Widgets.visible(widget) && widget.getActions() != null && MenuLabels.matches(widget.getName(), destination))
			{
				return widget;
			}
		}
		return null;
	}

	/** An option named exactly so wins over one that only starts the same: a ladder's "Climb-up" over its "Climb". */
	private static boolean isExactly(MenuEntry entry, String destination)
	{
		return destination != null && entry.getOption() != null
			&& MenuLabels.normalise(entry.getOption()).equals(MenuLabels.normalise(destination));
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

	private boolean isOptionOnTarget(MenuEntry entry, MenuTarget target)
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
		if (LeftClicks.OBJECT_OPTIONS.contains(entry.getType()) && sceneObjects.isFormOf(entry.getIdentifier(), target.getObjectIds()))
		{
			return true;
		}
		NPC npc = entry.getNpc();
		return npc != null && (target.getNpcIds().contains(npc.getId())
			|| npc.getTransformedComposition() != null && target.getNpcIds().contains(npc.getTransformedComposition().getId())
			|| npc.getName() != null && target.getNpcNames().contains(MenuLabels.normalise(npc.getName())));
	}

	/** Someone opened the exit's object already, so its first option goes on through it ("Go-down" on the trapdoor). */
	private boolean goesThroughOpened(MenuEntry entry, String destination)
	{
		if (!OPEN_OPTION.equalsIgnoreCase(destination) || entry.getType() != MenuAction.GAME_OBJECT_FIRST_OPTION
			|| CLOSE_OPTION.equalsIgnoreCase(MenuLabels.normalise(entry.getOption())))
		{
			return false;
		}
		ObjectComposition shown = LeftClicks.shownForm(client.getObjectDefinition(entry.getIdentifier()));
		return !Widgets.hasAction(shown.getActions(), OPEN_OPTION);
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

	/** The target's lines. */
	private static final class Found
	{
		final List<Widget> widgets = new ArrayList<>();
		/** A map's place buttons are boxed, not recoloured: their text sits on the map's art. */
		final Set<Widget> mapButtons = Collections.newSetFromMap(new IdentityHashMap<>());

		void add(Widget widget)
		{
			if (Widgets.visible(widget))
			{
				widgets.add(widget);
			}
		}

		void addMapButton(Widget button)
		{
			if (Widgets.visible(button))
			{
				widgets.add(button);
				mapButtons.add(button);
			}
		}
	}

	/** A nexus row ({@code ROWS1}) has no text; its label sits at the same height in the {@code TEXT1} layer. */
	private Widget labelOf(Widget row)
	{
		if (Widgets.hasText(row))
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
			if (label != null && Widgets.hasText(label) && middle >= top && middle < bottom)
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
