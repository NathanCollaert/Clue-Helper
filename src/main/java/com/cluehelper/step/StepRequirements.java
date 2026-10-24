package com.cluehelper.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.runelite.api.Item;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/** Core's item requirements are tested only through their own {@code fulfilledBy}, never taken apart. */
public final class StepRequirements
{
	private final SpadeNeed spade;
	private final boolean light;
	private final int firePitVarbitId;
	private final int lightSourceVarbitId;
	private final List<ItemRequirement> items;
	private final List<ItemRequirement> ownItems;
	private final int stashObjectId;

	StepRequirements(SpadeNeed spade, boolean light, int firePitVarbitId, int lightSourceVarbitId, List<ItemRequirement> items,
		int stashObjectId)
	{
		this(spade, light, firePitVarbitId, lightSourceVarbitId, items, List.of(), stashObjectId);
	}

	private StepRequirements(SpadeNeed spade, boolean light, int firePitVarbitId, int lightSourceVarbitId, List<ItemRequirement> items,
		List<ItemRequirement> ownItems, int stashObjectId)
	{
		this.spade = spade;
		this.light = light;
		this.firePitVarbitId = firePitVarbitId;
		this.lightSourceVarbitId = lightSourceVarbitId;
		this.items = List.copyOf(items);
		this.ownItems = List.copyOf(ownItems);
		this.stashObjectId = stashObjectId;
	}

	public static StepRequirements none()
	{
		return new StepRequirements(SpadeNeed.NONE, false, -1, -1, List.of(), -1);
	}

	public StepRequirements plus(ItemRequirement item)
	{
		List<ItemRequirement> more = new ArrayList<>(items);
		more.add(item);
		return new StepRequirements(spade, light, firePitVarbitId, lightSourceVarbitId, more, ownItems, stashObjectId);
	}

	/** An item the step's route needs that core's *clue* bank tag doesn't list: a source's tools, Charlie's item. */
	public StepRequirements plusOwn(ItemRequirement item)
	{
		List<ItemRequirement> more = new ArrayList<>(ownItems);
		more.add(item);
		return new StepRequirements(spade, light, firePitVarbitId, lightSourceVarbitId, items, more, stashObjectId);
	}

	public List<ItemRequirement> getOwnItems()
	{
		return ownItems;
	}

	/** The emote step's STASH unit object, or -1. */
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

	/** By value: a rewritten step (a probe trip, a stage dropped) is rebuilt every tick and must equal the last one. */
	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof StepRequirements))
		{
			return false;
		}
		StepRequirements that = (StepRequirements) other;
		return spade == that.spade && light == that.light && firePitVarbitId == that.firePitVarbitId
			&& lightSourceVarbitId == that.lightSourceVarbitId && stashObjectId == that.stashObjectId && items.equals(that.items)
			&& ownItems.equals(that.ownItems);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(spade, light, firePitVarbitId, lightSourceVarbitId, stashObjectId, items, ownItems);
	}

	@Override
	public String toString()
	{
		StringBuilder text = new StringBuilder();
		if (spade != SpadeNeed.NONE)
		{
			text.append("spade");
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
