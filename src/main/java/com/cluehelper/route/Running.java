package com.cluehelper.route;

/**
 * Running covers two tiles a tick on a straight path; corners, doors and stops brought the step campaign's walks to
 * 1.77 (929 walks, run on in all but 8), so a walked tile costs 13% more than half a tick.
 */
public final class Running
{
	private static final int PERCENT_OF_HALF_TICK = 113;

	private Running()
	{
	}

	public static int halfTicks(int tiles)
	{
		return (tiles * PERCENT_OF_HALF_TICK + 50) / 100;
	}
}
