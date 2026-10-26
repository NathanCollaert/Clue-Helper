package com.cluehelper.hotcold;

import static org.junit.Assert.assertTrue;

import com.cluehelper.step.HotColdTier;
import com.cluehelper.step.StepIndex;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

public class HypothesesTest
{
	/**
	 * Seen live: the last clue's anchor at Falador made Ice Mountain's "cold, but colder than last time" rule out the
	 * Lumbridge cow field, and the wheat field dug was wrong.
	 */
	@Test
	public void carriedAnchorRulesNoSpotOut() throws IOException
	{
		List<Spot> spots = Spots.of(StepIndex.read(new Gson()), HotColdTier.BEGINNER);
		List<Reading> log = List.of(new Reading(2949, 3387, 0, null, null),
			new Reading(3002, 3471, 0, Band.COLD, Comparison.COLDER));

		Set<String> kept = Hypotheses.fitting(spots, HotColdTier.BEGINNER, log, Set.of()).keptSpots().stream()
			.map(Spot::getName).collect(Collectors.toSet());

		assertTrue(kept + " keeps both", kept.containsAll(Set.of("DRAYNOR_WHEAT_FIELD", "LUMBRIDGE_COW_FIELD")));
	}
}
