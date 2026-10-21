package com.cluehelper.ui;

public enum BoxState
{
	DORMANT("Off on this world",
		"Clue Helper stays off on seasonal, Deadman, beta and other special worlds."),
	CORE_OFF("Turn the Clue Scroll plugin back on",
		"Clue Helper works alongside RuneLite's Clue Scroll plugin. Turn it back on to continue."),
	READ_CLUE("Read the clue to start",
		"The Clue Scroll plugin knows a clue only once it has been read."),
	NO_CLUE(null, null),
	WORKING("Working…",
		"Ranking routes from what you have."),
	ROUTES(null, null);

	private final String title;
	private final String detail;

	BoxState(String title, String detail)
	{
		this.title = title;
		this.detail = detail;
	}

	public String getTitle()
	{
		return title;
	}

	public String getDetail()
	{
		return detail;
	}

	public boolean showsRoutes()
	{
		return this == WORKING || this == ROUTES;
	}

	/** The box is always in view, so core off and dormant would nag a player who isn't doing a clue. */
	public boolean shownWhen(boolean carryingClue)
	{
		switch (this)
		{
			case NO_CLUE:
				return false;
			case CORE_OFF:
			case DORMANT:
				return carryingClue;
			default:
				return true;
		}
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
