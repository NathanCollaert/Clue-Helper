package com.cluehelper.route;

import com.cluehelper.AvoidWilderness;
import com.cluehelper.ClueHelperConfig;
import com.cluehelper.SpellbookChanges;
import lombok.Builder;
import lombok.Value;

/** The config the planner reads, copied on the client thread. Penalties are ticks and affect ranking only. */
@Value
@Builder(toBuilder = true)
public class PlannerSettings
{
	private static final int HARDCORE_IRONMAN = 3;
	private static final int HARDCORE_GROUP_IRONMAN = 5;

	@Builder.Default
	int routeCount = 3;
	@Builder.Default
	SpellbookChanges spellbookChanges = SpellbookChanges.ALL;
	boolean avoidWilderness;
	int consumablePenalty;
	@Builder.Default
	int capeUsePenalty = 3;
	@Builder.Default
	int strandPenalty = 30;
	/** The bank-stop edge is offered whenever {@code PlanInput.banked} is set (useBank on, a bank seen, not UIM). */
	@Builder.Default
	boolean bankEdge = true;
	/** Ticks on top of B14's bank stop (3 + 2 per item stack + 4 for the PIN). */
	int bankStopExtraTicks;

	public static PlannerSettings from(ClueHelperConfig config, int accountType)
	{
		boolean hardcore = accountType == HARDCORE_IRONMAN || accountType == HARDCORE_GROUP_IRONMAN;
		boolean avoid = config.avoidWilderness() == AvoidWilderness.ALWAYS
			|| config.avoidWilderness() == AvoidWilderness.HARDCORE_ONLY && hardcore;
		return PlannerSettings.builder()
			.routeCount(config.routeCount())
			.spellbookChanges(config.spellbookChanges())
			.avoidWilderness(avoid)
			.consumablePenalty(config.consumablePenalty())
			.capeUsePenalty(config.capeUsePenalty())
			.strandPenalty(config.strandPenalty())
			.bankEdge(config.useBank())
			.bankStopExtraTicks(config.bankStopExtraTicks())
			.build();
	}
}
