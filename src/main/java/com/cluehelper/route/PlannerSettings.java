package com.cluehelper.route;

import com.cluehelper.AvoidWilderness;
import com.cluehelper.ClueHelperConfig;
import com.cluehelper.SpellbookChanges;
import lombok.Builder;
import lombok.Value;

/** Copied on the client thread. */
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
	@Builder.Default
	boolean bankEdge = true;
	boolean scrollBookOutOfWilderness;

	public static PlannerSettings from(ClueHelperConfig config, int accountType)
	{
		boolean hardcore = accountType == HARDCORE_IRONMAN || accountType == HARDCORE_GROUP_IRONMAN;
		boolean avoid = config.avoidWilderness() == AvoidWilderness.ALWAYS
			|| config.avoidWilderness() == AvoidWilderness.HARDCORE_ONLY && hardcore;
		return PlannerSettings.builder()
			.spellbookChanges(config.spellbookChanges())
			.avoidWilderness(avoid)
			.bankEdge(config.useBank())
			.scrollBookOutOfWilderness(config.scrollBookOutOfWilderness())
			.build();
	}
}
