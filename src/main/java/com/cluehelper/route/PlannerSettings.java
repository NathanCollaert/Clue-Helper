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
	SpellbookChanges spellbookChanges = SpellbookChanges.ALL;
	boolean avoidWilderness;
	/** Implies {@link #avoidWilderness}: a step that needs the Wilderness is stopped instead of routed into it. */
	boolean stopWildernessClues;
	@Builder.Default
	boolean bankEdge = true;
	boolean scrollBookOutOfWilderness;
	@Builder.Default
	SaveConsumables saveConsumables = SaveConsumables.FLAT_FEE;

	public static PlannerSettings from(ClueHelperConfig config, AccountType accountType)
	{
		boolean stop = config.stopWildernessClues();
		boolean avoid = stop || config.avoidWilderness() == AvoidWilderness.ALWAYS
			|| config.avoidWilderness() == AvoidWilderness.HARDCORE_ONLY && accountType.isHardcore();
		return PlannerSettings.builder()
			.spellbookChanges(config.spellbookChanges())
			.avoidWilderness(avoid)
			.stopWildernessClues(stop)
			.bankEdge(config.useBank())
			.scrollBookOutOfWilderness(config.scrollBookOutOfWilderness())
			.saveConsumables(config.saveConsumables())
			.build();
	}
}
