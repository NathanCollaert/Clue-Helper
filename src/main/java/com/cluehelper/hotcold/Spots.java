package com.cluehelper.hotcold;

import com.cluehelper.step.*;
import java.util.List;
import static java.util.stream.Collectors.*;

/** Core's spots, as the step data bakes them from its enum, never from its solver. */
public final class Spots
{
	private Spots()
	{
	}

	public static List<Spot> of(StepIndex index, HotColdTier tier)
	{
		return index.getHotColdSpots().stream().filter(spot -> spot.getTier() == tier).collect(toUnmodifiableList());
	}
}
