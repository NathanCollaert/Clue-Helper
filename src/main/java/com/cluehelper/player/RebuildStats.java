package com.cluehelper.player;

import java.util.Map;
import java.util.TreeMap;

/** How often the snapshot is rebuilt, why, and what it costs (B08 verdict question 2). Client thread only. */
public final class RebuildStats
{
	private static final double NANOS_PER_MS = 1_000_000.0;
	private static final double TICKS_PER_MINUTE = 100.0;

	private final Map<String, Integer> byReason = new TreeMap<>();
	private final Map<String, Long> readNanos = new TreeMap<>();
	private int rebuilds;
	private long totalNanos;
	private long maxNanos;
	private int firstTick = -1;
	private int lastTick;

	void record(String reason, long nanos, int tick)
	{
		byReason.merge(reason, 1, Integer::sum);
		rebuilds++;
		totalNanos += nanos;
		maxNanos = Math.max(maxNanos, nanos);
		if (firstTick < 0)
		{
			firstTick = tick;
		}
		lastTick = tick;
	}

	void recordRead(String what, long nanos)
	{
		readNanos.put(what, nanos);
	}

	public String summary()
	{
		int ticks = Math.max(1, lastTick - firstTick);
		return String.format("%d rebuilds over %d ticks (%.1f/min), mean %.3f ms, max %.3f ms; by reason %s; reads %s",
			rebuilds, ticks, rebuilds * TICKS_PER_MINUTE / ticks, rebuilds == 0 ? 0 : totalNanos / NANOS_PER_MS / rebuilds,
			maxNanos / NANOS_PER_MS, byReason, readsInMs());
	}

	private Map<String, String> readsInMs()
	{
		Map<String, String> ms = new TreeMap<>();
		readNanos.forEach((what, nanos) -> ms.put(what, String.format("%.2f ms", nanos / NANOS_PER_MS)));
		return ms;
	}
}
