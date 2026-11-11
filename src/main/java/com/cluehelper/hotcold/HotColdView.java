package com.cluehelper.hotcold;

import com.cluehelper.step.HotColdTier;
import java.util.*;
import lombok.Value;

/**
 * The box's hot/cold lines. Where to feel or dig is the route's own guidance, so this adds only what the route can't
 * say: why there is no next feel, and what can hurt you.
 */
@Value
public class HotColdView
{
	/** The most a master feel dealt in 13 recorded feels. */
	private static final int MOST_FEEL_DAMAGE = 8;

	/** Null while feeling. */
	String title;
	List<String> warnings;

	@Value
	public static class Inputs
	{
		HotColdPlan plan;
		List<Spot> spots;
		HotColdTier tier;
		int hitpoints;
	}

	/** @return null when the route says it all */
	public static HotColdView of(Inputs in)
	{
		HotColdPlan plan = in.getPlan();
		if (plan == null)
		{
			return null;
		}
		Spot dug = plan.getAction() == HotColdPlan.Action.DIG_SPOT ? spot(in.getSpots(), plan) : null;
		List<String> warnings = new ArrayList<>();
		if (in.getTier() == HotColdTier.MASTER && plan.getAction() == HotColdPlan.Action.FEEL && in.getHitpoints() <= MOST_FEEL_DAMAGE)
		{
			warnings.add("HP " + in.getHitpoints() + ": the next feel can kill you");
		}
		if (dug != null && dug.isBorder())
		{
			warnings.add("Dig on the single-combat side to face one Brassican Mage");
		}
		String title = title(plan);
		return title == null && warnings.isEmpty() ? null : new HotColdView(title, List.copyOf(warnings));
	}

	private static String title(HotColdPlan plan)
	{
		return plan.getAction() == HotColdPlan.Action.NONE ? plan.getKept().size() + " spots left, and no feel tells them apart" : null;
	}

	private static Spot spot(List<Spot> spots, HotColdPlan plan)
	{
		String name = plan.getKept().keySet().iterator().next();
		return spots.stream().filter(spot -> spot.getName().equals(name)).findFirst().orElse(null);
	}
}
