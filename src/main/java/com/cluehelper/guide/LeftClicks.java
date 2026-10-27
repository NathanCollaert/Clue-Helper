package com.cluehelper.guide;

import com.cluehelper.memory.*;
import com.cluehelper.player.*;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.*;

/**
 * What a left click on an item, object or NPC does, Menu Entry Swapper's swaps included. Learned from the hover menu's
 * top entry once the mouse has been over it; before that, read from Menu Entry Swapper's config, else the first action.
 * Client thread only.
 */
@Singleton
public final class LeftClicks
{
	static final String KEY = "leftClicks";
	private static final String ITEM = "item:";
	private static final String WORN = "worn:";
	private static final String WORN_ID = "wornid:";
	private static final int SLOT_ITEM_CHILD = 1;
	private static final String OBJECT = "object:";
	private static final String NPC = "npc:";
	private static final String BANK = "bank:";
	private static final String WITHDRAW_OPTION = "Withdraw";
	private static final Set<MenuAction> NPC_OPTIONS = EnumSet.of(MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION,
		MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION);
	static final Set<MenuAction> OBJECT_OPTIONS = EnumSet.of(MenuAction.GAME_OBJECT_FIRST_OPTION,
		MenuAction.GAME_OBJECT_SECOND_OPTION, MenuAction.GAME_OBJECT_THIRD_OPTION, MenuAction.GAME_OBJECT_FOURTH_OPTION,
		MenuAction.GAME_OBJECT_FIFTH_OPTION);
	private static final Set<String> WEAR_OPTIONS = Set.of("Wear", "Wield", "Equip", "Hold");
	private static final String USE_OPTION = "Use";
	private static final String REMOVE_OPTION = "Remove";

	private final Client client;
	private final SwapperClicks swapperClicks;
	/** Kept in the profile, so a hover once is enough for every later session. */
	private final MemorySlot<Learned> remembered;

	@Inject
	LeftClicks(Client client, SwapperClicks swapperClicks, MemoryStore memory)
	{
		this.client = client;
		this.swapperClicks = swapperClicks;
		this.remembered = memory.slot(KEY, Learned.class, Learned::new);
	}

	/** @return whether the left click learned is new */
	boolean learn(MenuEntry hoverMenuTop)
	{
		String key = key(hoverMenuTop);
		String option = hoverMenuTop.getOption();
		return key != null && option != null && remember(key, option);
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

	/** A worn item's left click takes it off, as it does unless a swap or the item itself says otherwise. */
	boolean removes(int wornItemId)
	{
		String click = worn(wornItemId);
		return click == null || REMOVE_OPTION.equalsIgnoreCase(MenuLabels.normalise(click));
	}

	/** A task's item does the act on a left click when it is put on or used on another (a tinderbox, a herb). */
	boolean wearsOrUses(int itemId)
	{
		String click = item(itemId);
		return click != null && (WEAR_OPTIONS.contains(click) || USE_OPTION.equals(click));
	}

	/** A bank item's left click withdraws it, whatever the quantity, unless the bank hands out notes. */
	public boolean withdraws(int bankItemId)
	{
		String known = learned().get(BANK + bankItemId);
		boolean withdrawClick = known == null || known.startsWith(WITHDRAW_OPTION);
		return withdrawClick && client.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES) == 0;
	}

	String object(TileObject object)
	{
		ObjectComposition shown = shown(object.getId());
		String known = learned().get(OBJECT + shown.getId());
		if (known != null)
		{
			return known;
		}
		String swapped = swapperClicks.object(shown);
		return swapped != null ? swapped : firstAction(shown.getActions());
	}

	/** An ornate jewellery box's left click becomes the place it just sent the player to. */
	void learnBoxUsed(Set<Integer> boxIds, String destination)
	{
		boxIds.forEach(id -> remember(OBJECT + shown(id).getId(), destination));
	}

	/** Its left click was seen on a hover, not guessed from its definition. */
	boolean isLearned(TileObject object)
	{
		return learned().containsKey(OBJECT + shown(object.getId()).getId());
	}

	boolean isLearned(NPC npc)
	{
		return learned().containsKey(NPC + npc.getId());
	}

	/** Whether its shown form lists the option at all: an open trapdoor no longer offers "Open". */
	boolean offers(TileObject object, String option)
	{
		return Widgets.hasAction(shown(object.getId()).getActions(), option);
	}

	/** A multiloc whose form shown now lacks the option: a Dorgesh-Kaan lamp that isn't broken for this player. */
	boolean isShownWithout(TileObject object, String option)
	{
		return changesForm(object) && !offers(object, option);
	}

	/** A multiloc: what it shows, and offers, depends on the player's varbits. */
	boolean changesForm(TileObject object)
	{
		return client.getObjectDefinition(object.getId()).getImpostorIds() != null;
	}

	public boolean isLeftClick(TileObject object, String option)
	{
		String click = object(object);
		return click != null && click.equalsIgnoreCase(option);
	}

	/** Learned from the hover menu, else the NPC's first option. */
	String npc(NPC npc)
	{
		String known = learned().get(NPC + npc.getId());
		if (known != null)
		{
			return known;
		}
		NPCComposition definition = npc.getTransformedComposition();
		return definition == null ? null : firstAction(definition.getActions());
	}

	private String carried(int itemId)
	{
		String known = learned().get(ITEM + itemId);
		return known != null ? known : swapperClicks.item(itemId);
	}

	/**
	 * Worn items' options carry only the item name as the target, and a left click Menu Entry Swapper put on top (the Max
	 * cape's "Home") carries neither, so the slot's item id is the fallback key.
	 */
	private String worn(int itemId)
	{
		String byName = learned().get(WORN + MenuLabels.normalise(client.getItemDefinition(itemId).getName()));
		String known = byName != null ? byName : learned().get(WORN_ID + itemId);
		return known != null ? known : swapperClicks.worn(itemId);
	}

	/**
	 * A multiloc's own definition has no actions: its shown form's do, and each form clicks differently (Doris' trapdoor,
	 * 12266, opens as 12267 and goes down as 12268), so left clicks are learned per shown form.
	 */
	private ObjectComposition shown(int objectId)
	{
		return shownForm(client.getObjectDefinition(objectId));
	}

	/**
	 * The form a multiloc shows now, picked by the player's varbits (the house settings put the jewellery box's and
	 * nexus' left-click destination on it); the definition itself when it has one form, or shows none.
	 */
	public static ObjectComposition shownForm(ObjectComposition placed)
	{
		ObjectComposition impostor = placed.getImpostorIds() == null ? null : placed.getImpostor();
		return impostor != null ? impostor : placed;
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

	private String key(MenuEntry entry)
	{
		Widget widget = entry.getWidget();
		int group = widget == null ? -1 : WidgetUtil.componentToInterface(widget.getId());
		if (group == InterfaceID.INVENTORY && entry.getItemId() > 0)
		{
			return ITEM + entry.getItemId();
		}
		if (group == InterfaceID.BANKMAIN && entry.getItemId() > 0)
		{
			return BANK + entry.getItemId();
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
		return OBJECT_OPTIONS.contains(entry.getType()) ? OBJECT + shown(entry.getIdentifier()).getId() : null;
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

	private Map<String, String> learned()
	{
		return remembered.get().clicks;
	}

	/** @return whether the left click is new */
	private boolean remember(String key, String option)
	{
		if (option.equals(learned().get(key)))
		{
			return false;
		}
		Learned next = new Learned();
		next.clicks = new HashMap<>(learned());
		next.clicks.put(key, option);
		remembered.set(next);
		return true;
	}

	static final class Learned
	{
		Map<String, String> clicks = new HashMap<>();
	}
}
