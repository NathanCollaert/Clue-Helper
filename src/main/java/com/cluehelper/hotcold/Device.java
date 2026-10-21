package com.cluehelper.hotcold;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirements;

/** The strange device of each tier: a master device doesn't work on a beginner clue. */
public final class Device
{
	private static final ItemRequirement BEGINNER = ItemRequirements.item(ItemID.BEGINNER_DEVICE);
	private static final ItemRequirement MASTER = ItemRequirements.item(ItemID.MASTER_DEVICE);

	private Device()
	{
	}

	public static int itemId(boolean beginner)
	{
		return beginner ? ItemID.BEGINNER_DEVICE : ItemID.MASTER_DEVICE;
	}

	public static ItemRequirement requirement(boolean beginner)
	{
		return beginner ? BEGINNER : MASTER;
	}
}
