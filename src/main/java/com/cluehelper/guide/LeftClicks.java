package com.cluehelper.guide;

import com.cluehelper.player.Container;
import com.cluehelper.player.ItemUse;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.TileObject;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;

/**
 * What a left click on an item, object or NPC does, Menu Entry Swapper's swaps included. Learned from the hover menu's
 * top entry once the mouse has been over it; before that, read from Menu Entry Swapper's config, else the first action.
 * Client thread only.
 */
@Singleton
public final class LeftClicks
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
	private static final Set<String> WEAR_OPTIONS = Set.of("Wear", "Wield", "Equip", "Hold");
	private static final String USE_OPTION = "Use";

	private final Client client;
	private final SwapperClicks swapperClicks;
	private final Map<String, String> learned = new HashMap<>();

	@Inject
	LeftClicks(Client client, SwapperClicks swapperClicks)
	{
		this.client = client;
		this.swapperClicks = swapperClicks;
	}

	/** @return whether the left click learned is new */
	boolean learn(MenuEntry hoverMenuTop)
	{
		String key = key(hoverMenuTop);
		String option = hoverMenuTop.getOption();
		return key != null && option != null && !option.equals(learned.put(key, option));
	}

	String swappedOrLearned(ItemUse use)
	{
		return use.getContainer() == Container.WORN ? worn(use.getItemId()) : carried(use.getItemId());
	}

	/** Before a hover, a carried item no swap applies to left-clicks its first inventory action. */
	String item(ItemUse use)
	{
		return use.getContainer() == Container.WORN ? worn(use.getItemId()) : item(use.getItemId());
	}

	String item(int itemId)
	{
		String known = carried(itemId);
		return known != null ? known : firstAction(client.getItemDefinition(itemId).getInventoryActions());
	}

	boolean wears(int itemId)
	{
		String click = item(itemId);
		return click != null && WEAR_OPTIONS.contains(click);
	}

	/** A task's item does the act on a left click when it is put on or used on another (a tinderbox, a herb). */
	boolean wearsOrUses(int itemId)
	{
		String click = item(itemId);
		return click != null && (WEAR_OPTIONS.contains(click) || USE_OPTION.equals(click));
	}

	String object(TileObject object)
	{
		String known = learned.get(OBJECT + object.getId());
		if (known != null)
		{
			return known;
		}
		ObjectComposition definition = client.getObjectDefinition(object.getId());
		String swapped = swapperClicks.object(definition);
		return swapped != null ? swapped : defaultClick(definition);
	}

	public boolean isLeftClick(TileObject object, String option)
	{
		String click = object(object);
		return click != null && click.equalsIgnoreCase(option);
	}

	/** Learned from the hover menu, else the NPC's first option. */
	String npc(NPC npc)
	{
		String known = learned.get(NPC + npc.getId());
		if (known != null)
		{
			return known;
		}
		NPCComposition definition = npc.getTransformedComposition();
		return definition == null ? null : firstAction(definition.getActions());
	}

	private String carried(int itemId)
	{
		String known = learned.get(ITEM + itemId);
		return known != null ? known : swapperClicks.item(itemId);
	}

	/**
	 * Worn items' options carry only the item name as the target, and a left click Menu Entry Swapper put on top (the Max
	 * cape's "Home") carries neither, so the slot's item id is the fallback key.
	 */
	private String worn(int itemId)
	{
		String byName = learned.get(WORN + MenuLabels.normalise(client.getItemDefinition(itemId).getName()));
		String known = byName != null ? byName : learned.get(WORN_ID + itemId);
		return known != null ? known : swapperClicks.worn(itemId);
	}

	/** The house settings put the jewellery box's and nexus' left-click destination on their varbit-picked form. */
	private static String defaultClick(ObjectComposition object)
	{
		ObjectComposition shown = object.getImpostorIds() != null ? object.getImpostor() : object;
		return shown == null ? null : firstAction(shown.getActions());
	}

	private static String firstAction(String[] actions)
	{
		if (actions == null)
		{
			return null;
		}
		for (String action : actions)
		{
			if (action != null)
			{
				return action;
			}
		}
		return null;
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

	private static int slotItem(Widget slot)
	{
		if (slot.getItemId() > 0)
		{
			return slot.getItemId();
		}
		Widget item = slot.getChild(SLOT_ITEM_CHILD);
		return item == null ? -1 : item.getItemId();
	}
}
