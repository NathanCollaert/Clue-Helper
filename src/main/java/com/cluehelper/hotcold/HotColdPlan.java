package com.cluehelper.hotcold;

import com.cluehelper.step.HotColdTier;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

@Value
@Builder(toBuilder = true, access = AccessLevel.PACKAGE)
public class HotColdPlan
{
	public enum Action
	{
		FEEL,
		/** The last reading shook: any shaking tile is a dig tile. */
		DIG_HERE,
		/** One spot is left, and one dig covers every tile of it the grid keeps. */
		DIG_SPOT,
		/** Spots are left but no probe tells them apart. */
		NONE
	}

	String stepKey;
	HotColdTier tier;
	Action action;
	/** The probe tile or the dig tile; null for {@link Action#NONE}. */
	WorldPoint tile;
	/** Set for {@link Action#FEEL}. */
	ProbePlanner.Choice choice;
	/** Kept spot names to their share, largest first. */
	Map<String, Double> kept;
	boolean exact;
	int readings;
	int probes;
	int landings;
	long nanos;
	long landingNanos;
	/** Every probe the choice weighed, for replays. */
	@Builder.Default
	List<Probe> considered = List.of();

	/** The next feel, at the probe the choice picked. */
	static HotColdPlan feel(HotColdPlanBuilder base, ProbePlanner.Choice choice)
	{
		return base.action(Action.FEEL).tile(choice.getProbe().getTile()).choice(choice).build();
	}

	static HotColdPlan digHere(HotColdPlanBuilder base, WorldPoint tile)
	{
		return base.action(Action.DIG_HERE).tile(tile).build();
	}

	static HotColdPlan digSpot(HotColdPlanBuilder base, WorldPoint tile)
	{
		return base.action(Action.DIG_SPOT).tile(tile).build();
	}

	static HotColdPlan none(HotColdPlanBuilder base)
	{
		return base.action(Action.NONE).build();
	}

	public boolean isDig()
	{
		return action == Action.DIG_HERE || action == Action.DIG_SPOT;
	}

	public List<ProbePlanner.Branch> branches()
	{
		return choice == null ? List.of() : choice.getBranches();
	}
}
