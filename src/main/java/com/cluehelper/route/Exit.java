package com.cluehelper.route;

import java.util.Set;
import lombok.*;
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
	/** The other objects a row between the same tiles goes through (the Viyeldi winch before its rope is tied). */
	@With
	Set<Integer> otherForms;
	/** The chat option the talk picks; empty for none. */
	String chat;
}
