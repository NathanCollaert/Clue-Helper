package com.cluehelper.ui;

public enum PanelState
{
	DORMANT("Off on this world",
		"Clue Helper stays off on seasonal, Deadman, beta and other special worlds."),
	CORE_OFF("Turn the Clue Scroll plugin back on",
		"Clue Helper works alongside RuneLite's Clue Scroll plugin. Turn it back on to continue."),
	READ_CLUE("Read the clue to start",
		"The Clue Scroll plugin knows a clue only once it has been read."),
	NO_CLUE("No clue",
		"Read a clue scroll and the fastest ways to its step show here."),
	WORKING("Working…",
		"Ranking routes from what you have."),
	ROUTES(null, null);

	private final String title;
	private final String detail;

	PanelState(String title, String detail)
	{
		this.title = title;
		this.detail = detail;
	}

	/** Null for {@link #ROUTES}, which shows the route list instead of a status card. */
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

	/** A special world or core being off overrides everything: nothing else runs then. */
	public static PanelState of(boolean dormant, boolean coreActive, boolean clueNeedsRead, boolean hasStep, boolean planning)
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
