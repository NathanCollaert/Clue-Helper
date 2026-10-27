package com.cluehelper.guide;

import javax.inject.*;
import net.runelite.api.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemVariationMapping;

/**
 * Reads Menu Entry Swapper's config (never writes it) so the one-click fill shows before anything is hovered. Its
 * format: {@code object_<id>} holds the op index (0-4) or a sub-op packed as {@code ((sub + 1) << 8) | op};
 * {@code item_<id>} holds the op index, -1 for Use, or an option's hash; {@code wornitem_<id>} holds the menu identifier
 * or the option's hash. A hash naming a sub-menu line is left to hover learning. Client thread only.
 */
@Singleton
public class SwapperClicks
{
	private static final String GROUP = "menuentryswapper";
	private static final String PLUGIN_KEY = "menuentryswapperplugin";
	private static final String LEFT_CLICK_KEY = "leftClickCustomization";
	private static final String USE = "Use";
	private static final int SUB_OP_SHIFT = 8;
	private static final int OP_MASK = 0xFF;
	private static final int MAX_OP_INDEX = 4;
	private static final int FIRST_WORN_OPTION_ID = 2;
	private static final int FIRST_WORN_OPTION_PARAM = 451;
	private static final int LAST_WORN_OPTION_ID = 9;

	@Inject
	private Client client;
	@Inject
	private ConfigManager configManager;

	String object(ObjectComposition shown)
	{
		Integer value = swap("object_" + shown.getId());
		if (value == null || value < 0)
		{
			return null;
		}
		String[] actions = shown.getActions();
		if (value <= MAX_OP_INDEX)
		{
			return actions != null && value < actions.length ? actions[value] : null;
		}
		EntityOps ops = shown.getOps();
		EntityOps subs = ops == null ? null : ops.getSubOps(value & OP_MASK);
		int sub = (value >> SUB_OP_SHIFT) - 1;
		return subs == null || sub < 0 || sub >= subs.getNumOps() ? null : subs.getOp(sub);
	}

	String item(int itemId)
	{
		Integer value = swap("item_" + ItemVariationMapping.map(itemId));
		if (value == null)
		{
			return null;
		}
		if (value == -1)
		{
			return USE;
		}
		String[] actions = client.getItemDefinition(itemId).getInventoryActions();
		if (actions == null)
		{
			return null;
		}
		if (value >= 0 && value < actions.length)
		{
			return actions[value];
		}
		for (String action : actions)
		{
			if (action != null && action.hashCode() == value)
			{
				return action;
			}
		}
		return null;
	}

	String worn(int itemId)
	{
		Integer value = swap("wornitem_" + ItemVariationMapping.map(itemId));
		if (value == null)
		{
			return null;
		}
		ItemComposition item = client.getItemDefinition(itemId);
		for (int id = FIRST_WORN_OPTION_ID; id <= LAST_WORN_OPTION_ID; id++)
		{
			String option = item.getStringValue(FIRST_WORN_OPTION_PARAM + id - FIRST_WORN_OPTION_ID);
			if (option != null && !option.isEmpty() && (value == id || value == option.hashCode()))
			{
				return option;
			}
		}
		return null;
	}

	private Integer swap(String key)
	{
		if ("false".equals(configManager.getConfiguration("runelite", PLUGIN_KEY))
			|| "false".equals(configManager.getConfiguration(GROUP, LEFT_CLICK_KEY)))
		{
			return null;
		}
		String value = configManager.getConfiguration(GROUP, key);
		if (value == null || value.isEmpty())
		{
			return null;
		}
		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}
