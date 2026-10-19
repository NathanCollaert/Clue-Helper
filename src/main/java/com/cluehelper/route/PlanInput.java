package com.cluehelper.route;

import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.step.Step;
import com.cluehelper.transport.AvailabilityEngine;
import com.cluehelper.transport.Catalog;
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
	/** What the player has with the bank added (A_banked); null until B15 remembers the bank. */
	PlayerSnapshot banked;
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
}
