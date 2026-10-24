package com.cluehelper.hotcold;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

@Value
@Builder(toBuilder = true)
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

	public List<ProbePlanner.Branch> branches()
	{
		return choice == null ? List.of() : choice.getBranches();
	}
}
