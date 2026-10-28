package com.cluehelper.ui;

import lombok.*;

@Getter
@RequiredArgsConstructor
public enum BoxState
{
	DORMANT(null, null),
	CORE_OFF(null, null),
	READ_CLUE(null, null),
	NO_CLUE(null, null),
	WORKING("Working…",
		"Ranking routes from what you have."),
	ROUTES(null, null);

	private final String title;
	private final String detail;

	/** Only while the Clue Scroll plugin shows a clue: the box sits under core's and is never on screen alone. */
	public boolean showsRoutes()
	{
		return this == WORKING || this == ROUTES;
	}

	public static BoxState of(boolean dormant, boolean coreActive, boolean clueNeedsRead, boolean hasStep, boolean planning)
	{
		if (dormant)
		{
			return DORMANT;
		}
		if (!coreActive)
		{
			return CORE_OFF;
		}
		if (clueNeedsRead)
		{
			return READ_CLUE;
		}
		if (!hasStep)
		{
			return NO_CLUE;
		}
		return planning ? WORKING : ROUTES;
	}
}
