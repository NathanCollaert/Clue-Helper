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
 * Read from the hover menu's top entry, so Menu Entry Swapper's swaps are included. An item, object or NPC's left click
 * is unknown until the mouse has been over it once.
 */
final class LeftClicks
{
	private static final String ITEM = "item:";
	private static final String WORN = "worn:";
	private static final String WORN_ID = "wornid:";
	private static final int SLOT_ITEM_CHILD = 1;
	private static final String OBJECT = "object:";
	private static final String NPC = "npc:";
	private static final Set<MenuAction> NPC_OPTIONS = EnumSet.of(MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION,
		MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION);
	private static final Set<MenuAction> OBJECT_OPTIONS = EnumSet.of(MenuAction.GAME_OBJECT_FIRST_OPTION,
		MenuAction.GAME_OBJECT_SECOND_OPTION, MenuAction.GAME_OBJECT_THIRD_OPTION, MenuAction.GAME_OBJECT_FOURTH_OPTION,
		MenuAction.GAME_OBJECT_FIFTH_OPTION);

	private final Map<String, String> options = new HashMap<>();

	boolean learn(MenuEntry hoverMenuTop)
	{
		String key = key(hoverMenuTop);
		String option = hoverMenuTop.getOption();
		return key != null && option != null && !option.equals(options.put(key, option));
	}

	String item(int itemId)
	{
		return options.get(ITEM + itemId);
	}

	/**
	 * Worn items' options carry only the item name as the target, and a left click Menu Entry Swapper put on top (the Max
	 * cape's "Home") carries neither, so the slot's item id is the fallback key.
	 */
	String worn(int itemId, String itemName)
	{
		String byName = options.get(WORN + MenuLabels.normalise(itemName));
		return byName != null ? byName : options.get(WORN_ID + itemId);
	}

	private static int slotItem(Widget slot)
	{
		if (slot.getItemId() > 0)
		{
			return slot.getItemId();
		}
		Widget item = slot.getChild(SLOT_ITEM_CHILD);
		return item == null ? -1 : item.getItemId();
	}

	String object(int objectId)
	{
		return options.get(OBJECT + objectId);
	}

	String npc(int npcId)
	{
		return options.get(NPC + npcId);
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
		if (group == InterfaceID.WORNITEMS)
		{
			int slotItem = slotItem(widget);
			return slotItem > 0 ? WORN_ID + slotItem : null;
		}
		if (NPC_OPTIONS.contains(entry.getType()) && entry.getNpc() != null)
		{
			return NPC + entry.getNpc().getId();
		}
		return OBJECT_OPTIONS.contains(entry.getType()) ? OBJECT + entry.getIdentifier() : null;
	}
}
