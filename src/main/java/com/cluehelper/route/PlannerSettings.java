package com.cluehelper.route;

import com.cluehelper.AvoidWilderness;
import com.cluehelper.ClueHelperConfig;
import com.cluehelper.SpellbookChanges;
import lombok.Builder;
import lombok.Value;

/** Copied on the client thread. Penalties are in ticks and affect ranking only. */
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
	@Builder.Default
	boolean bankEdge = true;
	int bankStopExtraTicks;
	boolean scrollBookOutOfWilderness;

	public static PlannerSettings from(ClueHelperConfig config, int accountType)
	{
		boolean hardcore = accountType == HARDCORE_IRONMAN || accountType == HARDCORE_GROUP_IRONMAN;
		boolean avoid = config.avoidWilderness() == AvoidWilderness.ALWAYS
			|| config.avoidWilderness() == AvoidWilderness.HARDCORE_ONLY && hardcore;
		return PlannerSettings.builder()
			.spellbookChanges(config.spellbookChanges())
			.avoidWilderness(avoid)
			.consumablePenalty(config.consumablePenalty())
			.capeUsePenalty(config.capeUsePenalty())
			.strandPenalty(config.strandPenalty())
			.bankEdge(config.useBank())
			.bankStopExtraTicks(config.bankStopExtraTicks())
			.scrollBookOutOfWilderness(config.scrollBookOutOfWilderness())
			.build();
	}
}
