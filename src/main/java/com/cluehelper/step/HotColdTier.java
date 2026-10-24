package com.cluehelper.step;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirements;

/** The two tiers with hot/cold steps, each with its own strange device: a master device doesn't work on a beginner clue. */
public enum HotColdTier
{
	BEGINNER("beginner", ItemID.BEGINNER_DEVICE, "reldo"),
	MASTER("master", ItemID.MASTER_DEVICE, "jorral");

	private final String tierName;
	private final int deviceItemId;
	private final String deviceNpc;
	private final ItemRequirement device;

	HotColdTier(String tierName, int deviceItemId, String deviceNpc)
	{
		this.tierName = tierName;
		this.deviceItemId = deviceItemId;
		this.deviceNpc = deviceNpc;
		this.device = ItemRequirements.item(deviceItemId);
	}

	/** @param tier a hot/cold step's tier as the step names it, "beginner" or "master" */
	public static HotColdTier of(String tier)
	{
		for (HotColdTier candidate : values())
		{
			if (candidate.tierName.equals(tier))
			{
				return candidate;
			}
		}
		throw new IllegalArgumentException("no hot/cold tier " + tier);
	}

	/** The tier as a step names it. */
	public String getTierName()
	{
		return tierName;
	}

	public int getDeviceItemId()
	{
		return deviceItemId;
	}

	public ItemRequirement getDevice()
	{
		return device;
	}

	/** The NPC who hands out the device, as a step key names him. */
	public String getDeviceNpc()
	{
		return deviceNpc;
	}
}
