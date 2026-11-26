package com.cluehelper.memory;

import java.util.*;
import lombok.*;
import net.runelite.api.ObjectComposition;

/** What an allotment patch takes before a seed goes in, in the order a patch is picked to plant in: the least first. */
@Getter
@RequiredArgsConstructor
public enum Allotment
{
	EMPTY(null),
	WEEDS("Rake"),
	CROP("Harvest"),
	DEAD("Clear");

	/** The varbit's value once the weeds are raked off and nothing is planted. */
	private static final int WEEDED = 3;

	/** The patch's option that clears it; null for an empty one. */
	final String option;

	/** By the patch's varbit value and the form it shows for it; null while a crop is still growing. */
	public static Allotment of(int value, ObjectComposition shown)
	{
		if (value == WEEDED)
		{
			return EMPTY;
		}
		List<String> options = Arrays.asList(shown.getActions());
		return Arrays.stream(values()).filter(state -> state.option != null && options.contains(state.option)).findFirst().orElse(null);
	}
}
