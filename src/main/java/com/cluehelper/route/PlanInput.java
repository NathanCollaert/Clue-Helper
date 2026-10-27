package com.cluehelper.route;

import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.step.Step;
import com.cluehelper.transport.*;
import java.util.Set;
import lombok.*;

/** Snapshot data only, never the client, so a plan runs on any thread. */
@Value
@Builder(toBuilder = true)
public class PlanInput
{
	public static final int NO_REVERT_WINDOW = -1;

	Step step;
	PlayerSnapshot player;
	/** Null when no bank counts (UIM, useBank off, bank never seen). */
	PlayerSnapshot banked;
	boolean pinPending;
	boolean atOpenBank;
	@Builder.Default
	RequiredItems required = RequiredItems.NONE;
	AvailabilityEngine engine;
	long nowMinutes;
	Catalog catalog;
	WalkTable table;
	@Builder.Default
	NoTeleportAreas noTeleport = NoTeleportAreas.NONE;
	PlanStart start;
	PlannerSettings settings;
	/** Ticks left of a pending Spellbook Swap's free cast. */
	@Builder.Default
	int revertTicksLeft = NO_REVERT_WINDOW;
	/** Row names of spellbook changes and teleports the game refused this session; never offered. */
	@Builder.Default
	Set<String> refusedChanges = Set.of();
	boolean foreignHouseStart;
	/** The followed route's first transport key; null when no route of this step is followed. */
	String followedFirstTransport;
}
