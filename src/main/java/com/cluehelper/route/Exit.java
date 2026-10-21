package com.cluehelper.route;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * A walk row's menu action: a cave exit, a basement's stairs, a door, a guide NPC. The rows that jump far enough to
 * leave the loaded scene are the exits.
 */
@Value
public class Exit
{
	WorldPoint origin;
	WorldPoint destination;
	/** The menu option, e.g. "Climb-up". */
	String option;
	/** The object or NPC name, e.g. "Staircase"; empty when unknown. */
	String target;
	/** -1 when unknown. */
	int objectId;
}
