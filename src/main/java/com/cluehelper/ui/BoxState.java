package com.cluehelper.ui;

import lombok.*;

@Getter
@RequiredArgsConstructor
public enum BoxState
{
	/** No clue to guide: none open or not read yet, the Clue Scroll plugin off, or the plugin dormant. */
	NO_CLUE(null, null),
	/** The clue is done, but its emote's items still go back into the STASH unit. */
	PUT_BACK(null, null),
	WORKING("Working…",
		"Ranking routes from what you have."),
	ROUTES(null, null);

	final String title;
	final String detail;

	/**
	 * While the Clue Scroll plugin shows a clue, and after the last one while its STASH items are owed: the guide's
	 * steps are on screen.
	 */
	public boolean showsRoutes()
	{
		return this == WORKING || this == ROUTES || this == PUT_BACK;
	}

	/** @param owesPutBack an ended emote's items are still carried and go back into its STASH unit */
	public static BoxState of(boolean dormant, boolean coreActive, boolean hasStep, boolean owesPutBack,
		boolean planning)
	{
		if (dormant || !coreActive)
		{
			return NO_CLUE;
		}
		if (!hasStep)
		{
			return owesPutBack ? PUT_BACK : NO_CLUE;
		}
		return planning ? WORKING : ROUTES;
	}
}
