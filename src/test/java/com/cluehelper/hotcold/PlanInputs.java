package com.cluehelper.hotcold;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The probes a live plan was given, found again from its job: the plugin keeps none of them. Replaying the plan with
 * these gives its action and tile again ({@code HotColdDecisionReplayTest}). It plans the landing search again, so it
 * belongs off the client thread.
 */
public final class PlanInputs
{
	private PlanInputs()
	{
	}

	public static List<Probe> probes(ProbeJob job)
	{
		List<Probe> nearby = job.nearby();
		Set<Probe> probes = new LinkedHashSet<>(nearby);
		ProbeJob.decide(job.getStepKey(), job.getSpots(), job.getTier(), job.getReadings(), job.getCore(), nearby, limit ->
		{
			List<Probe> landings = job.landingsWithin(limit);
			probes.addAll(landings);
			return landings;
		}, job.getCosts());
		return new ArrayList<>(probes);
	}
}
