package com.cluehelper.hotcold;

import com.cluehelper.step.*;
import java.util.*;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * The hot/cold planner's chosen tile, which the route planner and the guide travel to instead of core's spots. Kept
 * apart from the planner so neither of them depends on it. Any thread.
 */
@Singleton
public class ProbeTarget
{
	@Value
	public static class Target
	{
		String stepKey;
		/** Null while the first plan is still being made. */
		WorldPoint tile;
		String act;
		boolean dig;
		/** Feel where you stand: the tile is where the plan was made, not a place to walk to. */
		boolean here;
	}

	private volatile Target target;
	private volatile boolean enabled;

	/** With the planner off a hot/cold step stays core's, which has no route. */
	public void setEnabled(boolean planner)
	{
		enabled = planner;
	}

	public void set(Target chosen)
	{
		target = chosen;
	}

	public void clear()
	{
		target = null;
	}

	/** Until the first plan arrives the step has no target, rather than core's NPC the player needn't visit. */
	public void choosing(String stepKey)
	{
		target = new Target(stepKey, null, null, false, false);
	}

	/**
	 * The step to route and guide: a hot/cold step goes to the chosen tile, or, before a choice, to the nearest of
	 * core's tiles (the NPC who gives the device, before the first feel). Any other step is returned as it is.
	 */
	public Step travelStep(Step step)
	{
		if (step == null || !enabled || !ClueTypes.HOT_COLD.equals(step.getType()) || step.getKind() != TargetKind.PROBES)
		{
			return step;
		}
		Target chosen = target;
		if (chosen == null || !chosen.getStepKey().equals(step.getKey()))
		{
			return step.toBuilder().kind(TargetKind.ANY_OF).build();
		}
		if (chosen.getTile() == null)
		{
			return step.toBuilder().kind(TargetKind.ANY_OF).targets(List.of()).build();
		}
		HotColdTier tier = HotColdTier.of(step.getTier());
		return step.toBuilder()
			.kind(TargetKind.ANY_OF)
			.targets(List.of(chosen.getTile()))
			.requirements(step.getRequirements().plus(tier.getDevice()))
			.waypointAct(chosen.getAct())
			.actItems(chosen.isDig() ? Set.of() : Set.of(tier.getDeviceItemId()))
			.actAnywhere(chosen.isHere())
			.build();
	}
}
