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
	/** Off until B15 remembers the bank: the bank-stop edge is built but never offered live. */
	public static final boolean BANK_EDGE_LIVE = false;
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
	@Builder.Default
	boolean bankEdge = BANK_EDGE_LIVE;

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
			.build();
	}
}
