package com.cluehelper.hotcold;

import java.util.Set;
import lombok.Value;

/** The Clue Scroll plugin's hot/cold solver as last read. */
@Value
public class CoreSpots
{
	/** Nothing known of core, so nothing is checked against it. */
	public static final CoreSpots UNKNOWN = new CoreSpots(Set.of(), true);

	/** The spots core still holds; empty when it has none to go by. */
	Set<String> names;
	/** Whether core has seen a feel since it read the clue; until then our log may be the last clue's. */
	boolean felt;
}
