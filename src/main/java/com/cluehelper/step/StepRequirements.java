package com.cluehelper.step;

import java.util.*;
import lombok.*;
import net.runelite.api.Item;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/**
 * Core's item requirements are tested only through their own {@code fulfilledBy}, never taken apart. Equal by value: a
 * rewritten step (a probe trip, a stage dropped) is rebuilt every tick and must equal the last one.
 */
@Getter
@EqualsAndHashCode
public final class StepRequirements
{
	/** The {@code stashObjectId} of a step without a STASH unit. */
	public static final int NO_STASH = -1;

	private final SpadeNeed spade;
	@Getter(AccessLevel.NONE)
	private final boolean light;
	/** The varbit that says the step's fire pit is lit, so no light is needed; -1 for none. */
	private final int firePitVarbitId;
	/** Core's light-source varbit of a coordinate clue; -1 for none. */
	private final int lightSourceVarbitId;
	private final List<ItemRequirement> items;
	/** Items the step's route needs that core's *clue* bank tag doesn't list: Charlie's item. */
	private final List<ItemRequirement> ownItems;
	/** The emote step's STASH unit object, or {@link #NO_STASH}. */
	private final int stashObjectId;

	StepRequirements(SpadeNeed spade, boolean light, int firePitVarbitId, int lightSourceVarbitId, List<ItemRequirement> items,
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
		return new StepRequirements(SpadeNeed.NONE, false, -1, -1, List.of(), List.of(), NO_STASH);
	}

	public StepRequirements plus(ItemRequirement item)
	{
		List<ItemRequirement> more = new ArrayList<>(items);
		more.add(item);
		return new StepRequirements(spade, light, firePitVarbitId, lightSourceVarbitId, more, ownItems, stashObjectId);
	}

	public boolean hasStash()
	{
		return stashObjectId != NO_STASH;
	}

	public boolean needsLight()
	{
		return light;
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
