package com.cluehelper.guide;

import com.cluehelper.player.PositionRules;
import com.cluehelper.step.StepRequirements;
import java.util.*;
import javax.inject.*;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Client thread only. After an emote step whose items came out of its STASH unit, they go back before moving on; the
 * reminder outlives the step and ends on the deposit line, or 15 tiles away once the player has been back by the unit
 * (the Agility Pyramid's emote is done on top, its unit stands at the foot).
 */
@Singleton
final class DepositReminder
{
	private static final int DEPOSIT_TILES = 15;

	@Inject
	private ActWatcher actWatcher;
	private Pending pending;
	private boolean beenNear;

	/** The unit to put the items back into; null when nothing is owed. */
	WorldPoint getAt()
	{
		return pending == null ? null : pending.getAt();
	}

	int getStashId()
	{
		return pending == null ? StepRequirements.NO_STASH : pending.getStashId();
	}

	void start(WorldPoint at, int stashId, List<String> items, int tick)
	{
		pending = new Pending(at, tick, stashId, items);
		beenNear = false;
	}

	void onGameTick(WorldPoint tile)
	{
		if (pending == null || tile == null)
		{
			return;
		}
		boolean deposited = actWatcher.isDone(SubStep.Done.STASH_DEPOSITED, pending.getSince());
		boolean near = PositionRules.near(tile, pending.getAt(), DEPOSIT_TILES);
		beenNear |= near;
		if (deposited || beenNear && !near)
		{
			pending = null;
		}
	}

	/** The walk back to the unit and the deposit, before {@code base}, while the items are owed. */
	List<SubStep> before(List<SubStep> base, WorldPoint tile)
	{
		if (pending == null || tile == null)
		{
			return base;
		}
		List<SubStep> all = new ArrayList<>();
		all.add(SubStep.of(SubStep.Kind.WALK, "Walk to the STASH unit").waypoint(pending.getAt()).goal(pending.getAt())
			.radius(SubSteps.ARRIVE_TILES).done(SubStep.Done.STASH_DEPOSITED).build());
		all.add(SubStep.of(SubStep.Kind.ACT, "Search the STASH unit: put back the " + String.join(" and ", pending.getItems()))
			.detail("Before moving on").done(SubStep.Done.STASH_DEPOSITED).build());
		all.addAll(base);
		return List.copyOf(all);
	}

	void clear()
	{
		pending = null;
		beenNear = false;
	}

	@Value
	private static class Pending
	{
		WorldPoint at;
		int since;
		int stashId;
		/** The STASH items the player still carries, to put back. */
		List<String> items;
	}
}
