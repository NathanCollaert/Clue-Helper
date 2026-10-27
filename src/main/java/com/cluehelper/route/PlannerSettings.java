package com.cluehelper.route;

import com.cluehelper.*;
import com.cluehelper.player.AccountType;
import lombok.*;

/** Copied on the client thread. */
@Value
@Builder(toBuilder = true)
public class PlannerSettings
{
	@Builder.Default
	int routeCount = 3;
	@Builder.Default
	SpellbookChanges spellbookChanges = SpellbookChanges.ALL;
	boolean avoidWilderness;
	@Builder.Default
	boolean bankEdge = true;
	boolean scrollBookOutOfWilderness;

	public static PlannerSettings from(ClueHelperConfig config, AccountType accountType)
	{
		boolean avoid = config.avoidWilderness() == AvoidWilderness.ALWAYS
			|| config.avoidWilderness() == AvoidWilderness.HARDCORE_ONLY && accountType.isHardcore();
		return PlannerSettings.builder()
			.spellbookChanges(config.spellbookChanges())
			.avoidWilderness(avoid)
			.bankEdge(config.useBank())
			.scrollBookOutOfWilderness(config.scrollBookOutOfWilderness())
			.build();
	}
}
