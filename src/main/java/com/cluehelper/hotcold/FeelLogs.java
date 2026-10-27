package com.cluehelper.hotcold;

import java.util.*;

/**
 * Our own record of each hot/cold step's readings, the anchor the game compares against: it keeps it across logout,
 * hop, teleport and bank, which core doesn't. Stored as JSON per RuneScape profile, so its fields are its format.
 */
public final class FeelLogs
{
	/** A beginner and a master step held at once, each possibly with a League Tutor twin. */
	private static final int MOST_STEPS = 4;

	private Map<String, List<Reading>> steps = new LinkedHashMap<>();

	public List<Reading> get(String stepKey)
	{
		List<Reading> readings = steps == null ? null : steps.get(stepKey);
		return readings == null ? List.of() : List.copyOf(readings);
	}

	/** A reading without a comparison is a step's first, so it starts the step's log afresh. */
	public FeelLogs with(String stepKey, Reading reading)
	{
		FeelLogs next = copy();
		List<Reading> readings = reading.getComparison() == null && reading.getBand() != Band.SHAKING
			? new ArrayList<>() : new ArrayList<>(next.steps.getOrDefault(stepKey, List.of()));
		readings.add(reading);
		next.steps.remove(stepKey);
		next.steps.put(stepKey, readings);
		Iterator<String> oldest = next.steps.keySet().iterator();
		while (next.steps.size() > MOST_STEPS)
		{
			oldest.next();
			oldest.remove();
		}
		return next;
	}

	/**
	 * A solved step's readings say nothing about the next spot, but the game still compares the next clue's first
	 * reading of the tier with the last one, so that one stays as the anchor.
	 */
	public FeelLogs solved(String stepKey)
	{
		FeelLogs next = copy();
		List<Reading> readings = next.steps.remove(stepKey);
		if (readings != null && !readings.isEmpty())
		{
			next.steps.put(stepKey, new ArrayList<>(List.of(readings.get(readings.size() - 1).asAnchor())));
		}
		return next;
	}

	/** Readings of the step, anchors left out. */
	public static int feels(List<Reading> readings)
	{
		return (int) readings.stream().filter(reading -> !reading.isAnchor()).count();
	}

	/** The most recent step of the tier with a log, or null. */
	public String latestKey(String tierPrefix)
	{
		String latest = null;
		if (steps != null)
		{
			for (String key : steps.keySet())
			{
				if (key.startsWith(tierPrefix))
				{
					latest = key;
				}
			}
		}
		return latest;
	}

	private FeelLogs copy()
	{
		FeelLogs next = new FeelLogs();
		if (steps != null)
		{
			steps.forEach((key, readings) -> next.steps.put(key, new ArrayList<>(readings)));
		}
		return next;
	}
}
