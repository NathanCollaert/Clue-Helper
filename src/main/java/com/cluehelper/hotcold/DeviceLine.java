package com.cluehelper.hotcold;

import lombok.Value;

/** The strange device's game messages, verbatim as B18 recorded them. */
@Value
public class DeviceLine
{
	public enum Kind
	{
		READING,
		/** Upstairs and in other places the device refuses; the game's anchor stays on the last real reading. */
		NOT_HERE,
		/** After every master reading, in the same tick. */
		HURT,
		/** A feel with no hot/cold step. */
		INACTIVE,
		/** The dig that gives the casket. */
		FOUND
	}

	private static final String READING_START = "The device is ";
	private static final String SHAKING_START = "visibly shaking";
	private static final String NOT_HERE_TEXT = "The strange device doesn't seem to work here.";
	private static final String HURT_TEXT = "The power of the strange device hurts you in the process.";
	private static final String INACTIVE_TEXT = "The strange device is inactive.";
	private static final String FOUND_TEXT = "The strange device cools as you find your treasure.";
	private static final String WARMER_TEXT = "warmer than last time";
	private static final String SAME_TEXT = "the same temperature as last time";
	private static final String COLDER_TEXT = "colder than last time";

	Kind kind;
	Band band;
	/** Null on a step's first reading and on the shaking line. */
	Comparison comparison;

	/** @param text the message without tags; null when it isn't one of the device's lines */
	public static DeviceLine parse(String text)
	{
		if (text == null)
		{
			return null;
		}
		switch (text)
		{
			case NOT_HERE_TEXT:
				return new DeviceLine(Kind.NOT_HERE, null, null);
			case HURT_TEXT:
				return new DeviceLine(Kind.HURT, null, null);
			case INACTIVE_TEXT:
				return new DeviceLine(Kind.INACTIVE, null, null);
			case FOUND_TEXT:
				return new DeviceLine(Kind.FOUND, null, null);
			default:
				return reading(text);
		}
	}

	private static DeviceLine reading(String text)
	{
		if (!text.startsWith(READING_START))
		{
			return null;
		}
		String rest = text.substring(READING_START.length());
		if (rest.startsWith(SHAKING_START))
		{
			return new DeviceLine(Kind.READING, Band.SHAKING, null);
		}
		int end = rest.indexOf(',');
		Band band = Band.fromClause(end < 0 ? stripPeriod(rest) : rest.substring(0, end));
		if (band == null)
		{
			return null;
		}
		return new DeviceLine(Kind.READING, band, comparison(rest));
	}

	private static Comparison comparison(String rest)
	{
		if (rest.contains(WARMER_TEXT))
		{
			return Comparison.WARMER;
		}
		if (rest.contains(SAME_TEXT))
		{
			return Comparison.SAME;
		}
		return rest.contains(COLDER_TEXT) ? Comparison.COLDER : null;
	}

	private static String stripPeriod(String clause)
	{
		return clause.endsWith(".") ? clause.substring(0, clause.length() - 1) : clause;
	}
}
