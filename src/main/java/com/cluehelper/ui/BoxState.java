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
	/** The clue is done, but its emote's items still go back into the STASH unit. */
	PUT_BACK(null, null),
	WORKING("Working…",
		"Ranking routes from what you have."),
	ROUTES(null, null);

	private final String title;
	private final String detail;

	/**
	 * While the Clue Scroll plugin shows a clue, and after the last one while its STASH items are owed: the guide's
	 * steps are on screen.
	 */
	public boolean showsRoutes()
	{
		return this == WORKING || this == ROUTES || this == PUT_BACK;
	}

	/** @param owesPutBack an ended emote's items are still carried and go back into its STASH unit */
	public static BoxState of(boolean dormant, boolean coreActive, boolean clueNeedsRead, boolean hasStep, boolean owesPutBack,
		boolean planning)
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
			return owesPutBack ? PUT_BACK : NO_CLUE;
		}
		return planning ? WORKING : ROUTES;
	}
}
