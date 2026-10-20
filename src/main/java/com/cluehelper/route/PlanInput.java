package com.cluehelper.route;

import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.step.Step;
import com.cluehelper.transport.AvailabilityEngine;
import com.cluehelper.transport.Catalog;
import java.util.Set;
import lombok.Builder;
import lombok.Value;

/** Everything one plan reads: snapshot data only, never the client, so it runs on any thread. */
@Value
@Builder(toBuilder = true)
public class PlanInput
{
	public static final int NO_REVERT_WINDOW = -1;

	Step step;
	PlayerSnapshot player;
	/** What the player has with the remembered bank added (A_banked); null when no bank counts (UIM, useBank off, never seen). */
	PlayerSnapshot banked;
	/** The next bank open asks for the PIN (B14: 4 ticks). */
	boolean pinPending;
	/** The bank interface is open: the plan starts banked, with no stop cost (PLAN §5.4 At an open bank). */
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
	/** Ticks left of a pending Spellbook Swap's free cast; {@link #NO_REVERT_WINDOW} when none or unknown. */
	@Builder.Default
	int revertTicksLeft = NO_REVERT_WINDOW;
	/** Spellbook changes the game refused this session (PLAN §5.4 Consumption); never offered. */
	@Builder.Default
	Set<String> refusedChanges = Set.of();
}
