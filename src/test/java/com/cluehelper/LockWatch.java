package com.cluehelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.runelite.api.coords.WorldPoint;

/**
 * The arrival lock after a landing or a crossing: the ticks before a walk click is accepted. The last walk click before
 * the player moved was accepted and bounds the lock from above; the earlier ones were dropped and bound it from below.
 *
 * <p>Ticks: the arrival is seen on GameTick with the count L of the period before its boundary, and a walk click in
 * period c is c - (L + 1) ticks into the lock. A dropped walk click less than {@link #LATE_MS} before a boundary may just
 * have reached the server a tick later, so it never counts as dropped.
 */
final class LockWatch
{
	static final int UNKNOWN = -1;
	private static final int TICK_MS = 600;
	private static final int LATE_MS = 150;

	private final List<WalkClick> walkClicks = new ArrayList<>();
	private long lastTickMs;
	private int lockStart;
	private WorldPoint arrivedAt;

	void onGameTick()
	{
		lastTickMs = System.currentTimeMillis();
	}

	void arrived(int arrivalTick, WorldPoint tile)
	{
		lockStart = arrivalTick + 1;
		arrivedAt = tile;
		walkClicks.clear();
	}

	void onWalkClick(int tick)
	{
		walkClicks.add(new WalkClick(tick, System.currentTimeMillis() - lastTickMs > TICK_MS - LATE_MS));
	}

	boolean hasMoved(WorldPoint now)
	{
		return !Objects.equals(now, arrivedAt);
	}

	Lock settle(WorldPoint now)
	{
		List<WalkClick> dropped = new ArrayList<>(walkClicks);
		int atMost = UNKNOWN;
		if (hasMoved(now) && !dropped.isEmpty())
		{
			WalkClick accepted = dropped.remove(dropped.size() - 1);
			atMost = Math.max(0, accepted.tick - lockStart);
		}
		int atLeast = dropped.stream()
			.filter(click -> !click.late)
			.mapToInt(click -> click.tick - lockStart + 1)
			.max()
			.orElse(0);
		walkClicks.clear();
		return new Lock(Math.max(0, atLeast), atMost);
	}

	static final class Lock
	{
		static final Lock NONE = new Lock(0, 0);

		final int atLeast;
		/** {@link #UNKNOWN} when no accepted walk click told it. */
		final int atMost;

		Lock(int atLeast, int atMost)
		{
			this.atLeast = atLeast;
			this.atMost = atMost;
		}
	}

	private static final class WalkClick
	{
		private final int tick;
		/** Less than {@link #LATE_MS} before the period's boundary. */
		private final boolean late;

		private WalkClick(int tick, boolean late)
		{
			this.tick = tick;
			this.late = late;
		}
	}
}
