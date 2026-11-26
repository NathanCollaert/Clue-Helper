package com.cluehelper.guide;

import com.cluehelper.Tables;
import com.cluehelper.transport.MenuLine;
import java.awt.Color;
import java.awt.Rectangle;
import java.util.*;
import java.util.regex.*;
import java.util.function.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.*;
import static java.util.stream.Collectors.*;

/**
 * Teleport interfaces are read from their scripts' arguments, before better-teleport-menu rewrites them. Lines and menu
 * options are recoloured, never rewritten or hidden. One that carries its own colour (Easy Teleports colours its names,
 * every line alike) is boxed instead, as its colour would win over ours. Client thread only.
 */
@Singleton
public class MenuLines
{
	private static final Set<MenuAction> GROUND_ITEM_OPTIONS = EnumSet.of(MenuAction.GROUND_ITEM_FIRST_OPTION,
		MenuAction.GROUND_ITEM_SECOND_OPTION, MenuAction.GROUND_ITEM_THIRD_OPTION, MenuAction.GROUND_ITEM_FOURTH_OPTION,
		MenuAction.GROUND_ITEM_FIFTH_OPTION);
	private static final int LIST_TITLE = 219;
	private static final int LIST_TITLE_ALT = 9142;
	private static final int LIST_LINE = 218;
	private static final int LIST_LINE_ALT = 9143;
	private static final int NEXUS_LINE = 2675;
	private static final int BOX_LINE = 1688;
	public static final String HOTKEYS = "123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
	private static final int LIST_GROUP = 187;
	private static final Pattern COLOUR_TAG = Pattern.compile("<col=([0-9a-fA-F]+)>");
	/** Each right-click menu row's height; the menu's height is its title, its rows and this padding under them. */
	private static final int MENU_ROW_HEIGHT = 15;
	private static final int MENU_BOTTOM_PADDING = 3;
	private static final Pattern MINIGAME_PLACE_NUMBER = Pattern.compile("^\\d+\\. ");
	/** A minigame card's text is its name, a break, then its places. */
	private static final String LINE_BREAK = "<br>";
	private static final int NEXUS_GROUP = 17;
	private static final int BOX_GROUP = 590;
	/** The Pendant of Ates' and the balloon's maps have one button per place, by the place its catalog line names. */
	private static final Map<String, Integer> MAP_BUTTONS = Tables.byKey("map buttons", row -> row.id(0));
	private static final String MAP_TELEPORT = "Teleport";
	private static final String MAP_REGION = "Select";
	private static final String OPEN_OPTION = "Open";
	private static final int[] BOAT_LISTS = {InterfaceID.SailingBoatSelection.BOATS_CONTAINER_RECENT,
		InterfaceID.SailingBoatSelection.BOATS_CONTAINER};
	private static final String BOAT_LOCATION = "location ";
	private static final String BOAT_TELEPORT = "Teleport To";
	/** A button's frame is at most this much wider than its text; the boat's row around it is far wider. */
	private static final int FRAME_WIDTH_RATIO = 2;
	private static final int SAME_ROW_PIXELS = 4;
	private static final String CLOSE_OPTION = "Close";
	/** Same cyan as {@code Highlighter.COLOUR}, the colour of every Clue Helper highlight. */
	private static final int TEXT_COLOUR = 0x40D0FF;
	private static final int MOST_ICON_PIXELS_OFF = 24;
	/** The grey of the info box's item lines. */
	private static final int PICK_COLOUR = 0xBEBEBE;

	/** A hotkey or fairy ring code as the info box names it: upper case, in the colour of the menu line it presses. */
	static String keyText(String key)
	{
		return coloured(TEXT_COLOUR, key.toUpperCase(Locale.ROOT));
	}

	/** What to click in an item or object, apart from the white item it is clicked on. */
	static String pickColoured(String text)
	{
		return coloured(PICK_COLOUR, text);
	}

	private static String coloured(int rgb, String text)
	{
		return String.format("<col=%06x>%s</col>", rgb, text);
	}

	@Inject
	Client client;
	@Inject
	FairyDials fairyDials;
	@Inject
	SceneObjects sceneObjects;
	@Inject
	EasyTeleportsNames easyTeleportsNames;
	final Map<String, int[]> listLines = new HashMap<>();
	String listTitle;
	/** The open list's labels as the game passed them, tags and all, before any plugin rewrites the lines. */
	final List<String> listLabels = new ArrayList<>();
	boolean listFresh;
	final Map<Integer, int[]> nexusLines = new HashMap<>();
	final Map<Integer, int[]> boxLines = new HashMap<>();
	final Map<Widget, Integer> originalTextColours = new IdentityHashMap<>();
	/** The open menu's options the guide marks, coloured or boxed. */
	final List<MenuEntry> markedOptions = new ArrayList<>();

	/** Call from a handler at priority 2, ahead of better-teleport-menu's at 1. */
	public void onScriptPreFired(ScriptPreFired event)
	{
		switch (event.getScriptId())
		{
			case LIST_TITLE:
			case LIST_TITLE_ALT:
				listLines.clear();
				listLabels.clear();
				listTitle = objectTail();
				break;
			case LIST_LINE:
			case LIST_LINE_ALT:
			{
				int[] ints = intTail(6);
				String label = objectTail();
				if (ints != null && label != null)
				{
					int[] line = {ints[0], ints[3]};
					listLines.put(MenuLabels.normalise(label), line);
					listLabels.add(label);
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
	 * Once per list, a tick after its lines are drawn. Hands over the labels as the game passed them: it greys a planted
	 * spirit tree that can't be used in the label itself, which another plugin may recolour once drawn.
	 */
	public void takeFreshList(BiConsumer<String, List<String>> reader)
	{
		if (!listFresh)
		{
			return;
		}
		listFresh = false;
		reader.accept(listTitle, List.copyOf(listLabels));
	}

	public boolean isListOpen(String title)
	{
		return !listLines.isEmpty() && MenuLabels.matches(listTitle, title);
	}

	/** The open list's line named so (also a map button drawn over the list), counted from 0; -1 for none. */
	public int listIndex(String label)
	{
		int[] line = listLines.get(MenuLabels.normalise(label));
		return line == null ? -1 : line[1];
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

	/**
	 * The teleport's own click on its item: the option the target marks, or any while it marks none (an item with one
	 * teleport). Another option (the scroll book's default while Open is marked) or one that only opens a list (the book's
	 * Open) is no click of the teleport's, so the game's answer to it is no refusal.
	 */
	boolean isTeleportClick(MenuTarget target, String option)
	{
		String destination = target.destination();
		return destination == null || shows(option, destination) && !MenuLabels.isOpener(destination);
	}

	/** A click on one of the target's map buttons (a balloon's destination) is its teleport's click. */
	boolean isMapButton(MenuTarget target, Widget clicked)
	{
		return clicked != null && lineWidgets(target).mapButtons.stream().anyMatch(button -> button.getId() == clicked.getId());
	}

	/** Only the target's lines without text (map icons, region shields, travel log codes); text lines are coloured. */
	public List<Rectangle> lines(MenuTarget target)
	{
		List<Rectangle> out = new ArrayList<>();
		Found found = lineWidgets(target);
		for (Widget widget : found.widgets)
		{
			boolean boxed = found.mapButtons.contains(widget) || !Widgets.hasTextInside(widget) || isColouredByEasyTeleports(widget);
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
	 * @param scroll a scroll whose book entry's name and charges are coloured too, or null
	 */
	public void colourLines(MenuTarget target, ScrollBook.Scroll scroll, boolean enabled)
	{
		Set<Widget> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
		if (enabled)
		{
			Found found = lineWidgets(target);
			found.widgets.stream().filter(line -> !found.mapButtons.contains(line) && !isColouredByEasyTeleports(line))
				.flatMap(line -> Widgets.descendants(line).stream()).filter(Widgets::hasText).forEach(wanted::add);
			if (scroll != null)
			{
				for (int text : new int[]{scroll.getCharges(), scroll.getTitle()})
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

	private boolean isColouredByEasyTeleports(Widget line)
	{
		return Widgets.descendants(line).stream().anyMatch(text -> isColouredByEasyTeleports(text.getText()));
	}

	/**
	 * Easy Teleports colours the names it shows again on every frame, so the guide boxes such a line instead of
	 * recolouring it. A colour tag on any other line, the game's own included, is recoloured.
	 */
	private boolean isColouredByEasyTeleports(String text)
	{
		return hasOwnColour(text) && easyTeleportsNames.renamed(text);
	}

	/** A colour tag other than ours: another plugin's or the game's. */
	private static boolean hasOwnColour(String text)
	{
		Matcher tag = text == null ? null : COLOUR_TAG.matcher(text);
		while (tag != null && tag.find())
		{
			if (Integer.parseInt(tag.group(1), 16) != TEXT_COLOUR)
			{
				return true;
			}
		}
		return false;
	}

	/** Call on {@code MenuOpened}, before {@link #colourMenu}: the options marked in the last menu are dropped. */
	public void forgetMenu()
	{
		markedOptions.clear();
	}

	/** The marked options that Easy Teleports colours, boxed where the open menu draws them. */
	public List<Rectangle> boxedOptions()
	{
		if (!client.isMenuOpen())
		{
			return List.of();
		}
		Menu top = client.getMenu();
		List<Menu> menus = new ArrayList<>(List.of(top));
		Arrays.stream(top.getMenuEntries()).map(MenuEntry::getSubMenu).filter(Objects::nonNull).forEach(menus::add);
		List<Rectangle> out = new ArrayList<>();
		for (MenuEntry option : markedOptions)
		{
			if (!isColouredByEasyTeleports(option.getOption()))
			{
				continue;
			}
			menus.stream().map(menu -> rowOf(menu, option)).filter(Objects::nonNull).findFirst().ifPresent(out::add);
		}
		return out;
	}

	/** The rows are drawn last entry first, under the menu's title. */
	private static Rectangle rowOf(Menu menu, MenuEntry option)
	{
		MenuEntry[] entries = menu.getMenuEntries();
		int index = Arrays.asList(entries).indexOf(option);
		if (index < 0)
		{
			return null;
		}
		int title = menu.getMenuHeight() - entries.length * MENU_ROW_HEIGHT - MENU_BOTTOM_PADDING;
		int row = entries.length - 1 - index;
		return new Rectangle(menu.getMenuX(), menu.getMenuY() + title + row * MENU_ROW_HEIGHT, menu.getMenuWidth(), MENU_ROW_HEIGHT);
	}

	/** Call on {@code MenuOpened}. Each alternative target marks its own option, on its own item. */
	public void colourMenu(MenuTarget target, boolean enabled)
	{
		if (!enabled || target == null || target == MenuTarget.NONE)
		{
			return;
		}
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		int row = pickRow(entries, target);
		if (row >= 0)
		{
			colour(entries[row]);
			colourSubMenuLine(entries[row].getSubMenu(), target.destination());
		}
		target.getAlternatives().forEach(alternative -> colourMenu(alternative, true));
	}

	/** Only the topmost line naming the destination, else the one its hotkey picks. */
	private void colourSubMenuLine(Menu sub, String destination)
	{
		if (sub == null || destination == null)
		{
			return;
		}
		MenuEntry[] options = sub.getMenuEntries();
		boolean exact = Arrays.stream(options).anyMatch(option -> namesExactly(option.getOption(), destination));
		for (int i = options.length - 1; i >= 0; i--)
		{
			if (exact ? namesExactly(options[i].getOption(), destination) : shows(options[i].getOption(), destination))
			{
				colour(options[i]);
				return;
			}
		}
		int line = MenuLabels.hotkeyIndex(destination);
		if (line >= 0 && line < options.length)
		{
			colour(options[options.length - 1 - line]);
		}
	}

	/** Coloured, unless Easy Teleports colours it: then it is boxed. */
	private void colour(MenuEntry entry)
	{
		markedOptions.add(entry);
		if (isColouredByEasyTeleports(entry.getOption()))
		{
			return;
		}
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
		return addMapButtons(Arrays.asList(dynamicChildren(InterfaceID.FossilMushtrees.UNIVERSE)),
			button -> button.getName() != null && MenuLabels.matches(button.getName().replace(' ', ' '), destination), out);
	}

	/** Each place on the glider map is a button with the place's name as its action. */
	private boolean glider(String destination, Found out)
	{
		return addMapButtons(Widgets.descendants(client.getWidget(InterfaceID.Glidermap.UNIVERSE)),
			button -> namesPlace(button, destination), out);
	}

	/** @return whether any of the buttons is visible and names the place */
	private static boolean addMapButtons(List<Widget> buttons, Predicate<Widget> namesPlace, Found out)
	{
		boolean found = false;
		for (Widget button : buttons)
		{
			if (Widgets.visible(button) && namesPlace.test(button))
			{
				out.addMapButton(button);
				found = true;
			}
		}
		return found;
	}

	private boolean placeButton(String destination, Found out)
	{
		for (Map.Entry<String, Integer> place : MAP_BUTTONS.entrySet())
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
		return widget.getActions() != null
			&& Arrays.stream(widget.getActions()).anyMatch(action -> action != null && MenuLabels.matches(action, label));
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
				quetzal(target.getIcon(), out);
				break;
			case BOAT:
				boat(target.getLineName(), out);
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

	/** The row of the first kind found, in order of preference; -1 for none. */
	private int pickRow(MenuEntry[] entries, MenuTarget target)
	{
		String destination = target.destination();
		List<Predicate<MenuEntry>> preferred = List.of(entry -> isExactly(entry, destination),
			entry -> isDestination(entry, target, destination), entry -> leadsToDestination(entry, target, destination),
			entry -> MenuLabels.isOpener(entry.getOption()), entry -> goesThroughOpened(entry, destination));
		return preferred.stream().mapToInt(wanted -> find(entries, target, wanted)).filter(row -> row >= 0).findFirst().orElse(-1);
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
		Widget region = null;
		for (Widget icon : dynamicChildren(InterfaceID.TelenexusTeleport.FRAME))
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
		Widget row = client.getWidget(FairyDials.logRow(letters));
		out.add(row);
		if (row != null)
		{
			FairyLogFilter.codeBeside(row).forEach(out::add);
		}
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
		Widget line = lines == null ? null : Arrays.stream(lines)
			.filter(shown -> Widgets.visible(shown) && MenuLabels.matches(shown.getText(), name)).findFirst().orElse(null);
		out.add(line != null ? line : client.getWidget(InterfaceID.Grouping.DROPDOWN_TOP));
	}

	/**
	 * The Minigame Teleport spell opens a grid of cards, one per minigame, each with the game's name and its places;
	 * the card whose name is the row's game is boxed whole, icon and all. A row names the game before
	 * its place.
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

	/** A game with several places asks which one next. */
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
		return Widgets.descendants(widget).stream()
			.anyMatch(part -> part.getText() != null && MenuLabels.matches(part.getText().split(LINE_BREAK, 2)[0], label));
	}

	private void quetzal(int[] at, Found out)
	{
		Widget icons = client.getWidget(InterfaceID.QuetzalMenu.ICONS);
		if (!Widgets.visible(icons))
		{
			icons = client.getWidget(InterfaceID.QuetzalwhistleMenu.ICONS);
		}
		if (!Widgets.visible(icons))
		{
			return;
		}
		out.add(nearestIcon(icons.getDynamicChildren(), at));
	}

	private static Widget nearestIcon(Widget[] icons, int[] at)
	{
		Widget best = null;
		int bestOff = MOST_ICON_PIXELS_OFF + 1;
		for (Widget icon : icons == null ? new Widget[0] : icons)
		{
			if (icon == null || icon.isHidden())
			{
				continue;
			}
			int x = icon.getOriginalX();
			int y = icon.getOriginalY();
			int off = Math.min(off(x, y, at), off(x + icon.getWidth() / 2, y + icon.getHeight() / 2, at));
			if (off < bestOff)
			{
				best = icon;
				bestOff = off;
			}
		}
		return best;
	}

	private static int off(int x, int y, int[] at)
	{
		return Math.max(Math.abs(x - at[0]), Math.abs(y - at[1]));
	}

	/**
	 * Each boat in the Teleport to Boat list is a flat run of widgets: its name, a line naming its dock and a
	 * Teleport To button level with them. The button nearest the dock's line in height is that boat's.
	 */
	private void boat(String dock, Found out)
	{
		for (int list : BOAT_LISTS)
		{
			List<Widget> widgets = Widgets.descendants(client.getWidget(list));
			for (Widget location : widgets)
			{
				if (Widgets.visible(location) && Widgets.hasText(location) && isLocation(location.getText(), dock))
				{
					nearestTeleportButtons(widgets, location).forEach(out::add);
				}
			}
		}
	}

	private static boolean isLocation(String text, String dock)
	{
		String shown = MenuLabels.normalise(text);
		return shown.startsWith(BOAT_LOCATION) && shown.endsWith(MenuLabels.normalise(dock));
	}

	/** The button's text and its frame, when they are separate widgets at the same height. */
	private static List<Widget> nearestTeleportButtons(List<Widget> widgets, Widget location)
	{
		double rowMiddle = location.getBounds().getCenterY();
		List<Widget> buttons = widgets.stream().filter(Widgets::visible).filter(MenuLines::isBoatTeleportButton)
			.collect(toList());
		double nearest = buttons.stream().mapToDouble(button -> Math.abs(button.getBounds().getCenterY() - rowMiddle)).min()
			.orElse(Double.MAX_VALUE);
		return buttons.stream().filter(button -> Math.abs(button.getBounds().getCenterY() - rowMiddle) <= nearest + SAME_ROW_PIXELS)
			.map(button -> buttonFrame(widgets, button)).collect(toList());
	}

	/** The button's frame around its text, a separate widget with neither action nor text; the text when there is none. */
	private static Widget buttonFrame(List<Widget> widgets, Widget text)
	{
		Rectangle bounds = text.getBounds();
		return widgets.stream().filter(Widgets::visible).filter(frame -> frame != text && frame.getBounds().contains(bounds)
				&& frame.getBounds().width <= bounds.width * FRAME_WIDTH_RATIO)
			.min(Comparator.comparingDouble(frame -> frame.getBounds().getWidth() * frame.getBounds().getHeight())).orElse(text);
	}

	private static boolean isBoatTeleportButton(Widget widget)
	{
		return Widgets.hasAction(widget.getActions(), BOAT_TELEPORT)
			|| widget.getText() != null && BOAT_TELEPORT.equalsIgnoreCase(Text.removeTags(widget.getText()).trim());
	}

	private void labelled(MenuTarget target, Found out)
	{
		String destination = target.destination();
		if (destination == null || charter(destination, out) || glider(destination, out) || mushtree(destination, out)
			|| placeButton(destination, out))
		{
			return;
		}
		Map.Entry<String, int[]> listed = listLines.entrySet().stream().filter(line -> namesExactly(line.getKey(), destination)).findFirst()
			.or(() -> listLines.entrySet().stream().filter(line -> MenuLabels.matches(line.getKey(), destination)).findFirst()).orElse(null);
		if (listed != null)
		{
			Widget listLine = child(listed.getValue());
			out.add(Widgets.visible(listLine) ? listLine : drawnOverList(listed.getValue(), destination));
			return;
		}
		int index = MenuLabels.hotkeyIndex(destination);
		for (int[] line : listLines.values())
		{
			if (index >= 0 && line[1] == index)
			{
				out.add(child(line));
				return;
			}
		}
		List<Widget> options = Arrays.stream(dynamicChildren(InterfaceID.Chatmenu.OPTIONS)).filter(Widgets::visible).collect(toList());
		List<String> labels = target.getPath();
		for (int i = labels.size() - 1; i >= 0; i--)
		{
			String label = labels.get(i);
			Optional<Widget> option = options.stream().filter(line -> namesExactly(line.getText(), label)).findFirst()
				.or(() -> options.stream().filter(line -> shows(line.getText(), label)).findFirst());
			if (option.isPresent())
			{
				out.add(option.get());
				return;
			}
		}
	}

	/**
	 * A line that is the label itself, also under its Easy Teleports name, wins over one that only starts the same: the
	 * camulet's "Enakhra's Temple Entrance." over its "Enakhra's Temple.".
	 */
	private boolean namesExactly(String line, String label)
	{
		String wanted = MenuLabels.normalise(label);
		return line != null && easyTeleportsNames.readings(line).stream().anyMatch(reading -> MenuLabels.normalise(reading).equals(wanted));
	}

	/**
	 * Another plugin's map drawn over the hidden list (spirit-tree-map): its button for the destination, a widget in the
	 * list's interface named after it, with an action.
	 */
	private Widget drawnOverList(int[] listLine, String destination)
	{
		return Widgets.descendants(client.getWidget(listLine[0] >>> 16, 0)).stream()
			.filter(widget -> Widgets.visible(widget) && widget.getActions() != null && MenuLabels.matches(widget.getName(), destination))
			.findFirst().orElse(null);
	}

	/** An option named exactly so wins over one that only starts the same: a ladder's "Climb-up" over its "Climb". */
	private boolean isExactly(MenuEntry entry, String destination)
	{
		return destination != null && entry.getOption() != null && easyTeleportsNames.readings(entry.getOption()).stream()
			.anyMatch(option -> MenuLabels.normalise(option).equals(MenuLabels.normalise(destination)));
	}

	/** Whether the shown option or line names the label, also under a name Easy Teleports gave it. */
	private boolean shows(String shown, String label)
	{
		return easyTeleportsNames.readings(shown).stream().anyMatch(reading -> MenuLabels.matches(reading, label));
	}

	private boolean isDestination(MenuEntry entry, MenuTarget target, String destination)
	{
		if (target.getLine() == MenuLine.FAIRY_CODE && target.getLineKey() != null)
		{
			return MenuLabels.isRingDestination(entry.getOption(), target.getLineKey());
		}
		return destination != null && shows(entry.getOption(), destination);
	}

	private boolean leadsToDestination(MenuEntry entry, MenuTarget target, String destination)
	{
		List<String> path = target.getPath();
		for (int i = 0; i < path.size() - 1; i++)
		{
			if (shows(entry.getOption(), path.get(i)))
			{
				return true;
			}
		}
		Menu sub = entry.getSubMenu();
		return sub != null && destination != null
			&& Arrays.stream(sub.getMenuEntries()).anyMatch(option -> shows(option.getOption(), destination));
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
		if (widget != null && (target.getItemIds().contains(LeftClicks.itemOn(widget)) || target.getWidgetIds().contains(widget.getId())))
		{
			return true;
		}
		if (entry.getItemId() <= 0 && target.getItemNames().contains(MenuLabels.normalise(entry.getTarget())))
		{
			return true;
		}
		if (GROUND_ITEM_OPTIONS.contains(entry.getType()) && target.getItemIds().contains(entry.getIdentifier()))
		{
			return true;
		}
		if (LeftClicks.OBJECT_OPTIONS.contains(entry.getType()) && sceneObjects.isFormOf(entry.getIdentifier(), target.getObjectIds()))
		{
			return true;
		}
		NPC npc = entry.getNpc();
		return npc != null && (SceneObjects.isNpcOf(npc, sceneObjects.withNpcForms(target.getNpcIds()))
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
