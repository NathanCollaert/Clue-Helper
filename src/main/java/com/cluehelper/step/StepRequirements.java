package com.cluehelper.step;

import java.util.List;
import net.runelite.api.Item;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/**
 * What a step asks of the player, as core models it. Core's item requirements are tested only
 * through their own {@code fulfilledBy}, never taken apart.
 */
public final class StepRequirements
{
	private final SpadeNeed spade;
	private final boolean light;
	private final int firePitVarbitId;
	private final int lightSourceVarbitId;
	private final List<ItemRequirement> items;
	private final int stashObjectId;

	StepRequirements(SpadeNeed spade, boolean light, int firePitVarbitId, int lightSourceVarbitId, List<ItemRequirement> items,
		int stashObjectId)
	{
		this.spade = spade;
		this.light = light;
		this.firePitVarbitId = firePitVarbitId;
		this.lightSourceVarbitId = lightSourceVarbitId;
		this.items = List.copyOf(items);
		this.stashObjectId = stashObjectId;
	}

	/** The emote step's STASH unit object (core's {@code STASHUnit}), or -1. */
	public int getStashObjectId()
	{
		return stashObjectId;
	}

	public SpadeNeed getSpade()
	{
		return spade;
	}

	public boolean needsLight()
	{
		return light;
	}

	/** The varbit that says the step's fire pit is lit, so no light is needed; -1 for none. */
	public int getFirePitVarbitId()
	{
		return firePitVarbitId;
	}

	/** Core's light-source varbit of a coordinate clue; -1 for none. */
	public int getLightSourceVarbitId()
	{
		return lightSourceVarbitId;
	}

	public List<ItemRequirement> getItems()
	{
		return items;
	}

	public boolean itemsFulfilledBy(Item[] items)
	{
		for (ItemRequirement requirement : this.items)
		{
			if (!requirement.fulfilledBy(items))
			{
				return false;
			}
		}
		return true;
	}

	@Override
	public String toString()
	{
		StringBuilder text = new StringBuilder();
		if (spade != SpadeNeed.NONE)
		{
			text.append(spade == SpadeNeed.SPADE ? "spade" : "spade or dog");
		}
		if (light)
		{
			text.append(text.length() > 0 ? ", " : "").append("light");
			if (firePitVarbitId != -1)
			{
				text.append(" (fire pit ").append(firePitVarbitId).append(')');
			}
		}
		if (lightSourceVarbitId != -1)
		{
			text.append(text.length() > 0 ? ", " : "").append("light varbit ").append(lightSourceVarbitId);
		}
		if (!items.isEmpty())
		{
			text.append(text.length() > 0 ? ", " : "").append(items.size()).append(" item requirement").append(items.size() == 1 ? "" : "s");
		}
		return text.length() == 0 ? "none" : text.toString();
	}
}
