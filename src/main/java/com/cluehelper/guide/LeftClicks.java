package com.cluehelper.guide;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;

/**
 * The left-click option of the items and objects the mouse has been over, read from the hover menu's top entry after
 * Menu Entry Swapper's swaps (Nathan, B16: only that click counts as one click). Unknown until hovered once.
 */
final class LeftClicks
{
	private static final String ITEM = "item:";
	private static final String WORN = "worn:";
	private static final String OBJECT = "object:";
	private static final Set<MenuAction> OBJECT_OPTIONS = EnumSet.of(MenuAction.GAME_OBJECT_FIRST_OPTION,
		MenuAction.GAME_OBJECT_SECOND_OPTION, MenuAction.GAME_OBJECT_THIRD_OPTION, MenuAction.GAME_OBJECT_FOURTH_OPTION,
		MenuAction.GAME_OBJECT_FIFTH_OPTION);

	private final Map<String, String> options = new HashMap<>();

	/** The hover menu's top entry; true when it changed what is known. */
	boolean learn(MenuEntry top)
	{
		String key = key(top);
		String option = top.getOption();
		return key != null && option != null && !option.equals(options.put(key, option));
	}

	String item(int itemId)
	{
		return options.get(ITEM + itemId);
	}

	/** Worn items' options carry no item id, only the name as the target. */
	String worn(String itemName)
	{
		return options.get(WORN + MenuLabels.normalise(itemName));
	}

	String object(int objectId)
	{
		return options.get(OBJECT + objectId);
	}

	private static String key(MenuEntry entry)
	{
		Widget widget = entry.getWidget();
		int group = widget == null ? -1 : WidgetUtil.componentToInterface(widget.getId());
		if (group == InterfaceID.INVENTORY && entry.getItemId() > 0)
		{
			return ITEM + entry.getItemId();
		}
		if (group == InterfaceID.WORNITEMS && entry.getTarget() != null && !entry.getTarget().isEmpty())
		{
			return WORN + MenuLabels.normalise(entry.getTarget());
		}
		return OBJECT_OPTIONS.contains(entry.getType()) ? OBJECT + entry.getIdentifier() : null;
	}
}
