package com.cluehelper.memory;

import com.cluehelper.step.StepIndex;
import java.util.*;
import javax.inject.*;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Client thread only. Where the player went into a way in entered at one of several places, learnt as they go in: the
 * Barrows crypt with the tunnel is only known once the player has gone in from it. Kept across sessions until the way's
 * forget line shows in chat (the chest's count once it is looted).
 */
@Singleton
public class WayInMemory
{
	static final String KEY = "waysIn";
	/** Anywhere on one Barrows mound digs into the same crypt. */
	private static final int SAME_PLACE_TILES = 4;

	/**
	 * Where the player stepped onto the way's part-way floors (the mound dug into); stored with the way's forget line, so
	 * it is forgotten whatever step is on.
	 */
	@Value
	public static class Entered
	{
		WorldPoint from;
		String forgetOn;
	}

	MemorySlot<Ways> remembered;
	WorldPoint last;
	/** The tile the player last stepped from onto the way's part-way floors (a crypt), for the way being watched. */
	WorldPoint partWayFrom;
	/** The place {@link #partWayFrom} leads to was tried and doesn't lead in (no tunnel in that crypt). */
	boolean triedWrong;

	@Inject
	void init(MemoryStore memory)
	{
		remembered = memory.slot(KEY, Ways.class, Ways::new);
	}

	/** Each tick, with the current step's way in: notes the place the player goes in by. */
	public void onTick(StepIndex.WayIn way, WorldPoint at)
	{
		WorldPoint before = last;
		last = at;
		if (way == null || !way.isRemembered() || before == null || at == null)
		{
			return;
		}
		if (way.isPartWay(at) && !way.isPartWay(before) && !way.isInside(before))
		{
			partWayFrom = before;
			triedWrong = false;
		}
		else if (way.isInside(at) && way.isPartWay(before) && partWayFrom != null)
		{
			Ways ways = remembered.get().copy();
			ways.entered.put(way.getText(), new Entered(partWayFrom, way.getForgetOn()));
			remembered.set(ways);
		}
	}

	/** Whatever the step: the Barrows chest looted on any trip moves the tunnel. With the step's way, a wrong place tried. */
	public void onGameLine(StepIndex.WayIn way, String line)
	{
		StepIndex.WrongPlace wrong = way == null ? null : way.getWrongPlace();
		triedWrong |= wrong != null && partWayFrom != null && line.contains(wrong.getLine());
		Ways ways = remembered.get().copy();
		if (ways.entered.values().removeIf(entered -> entered.forgetOn != null && line.contains(entered.forgetOn)))
		{
			remembered.set(ways);
		}
	}

	/**
	 * Part way in at a place that doesn't lead in: one tried without luck, or another than the one remembered (a crypt dug
	 * into elsewhere on the mounds).
	 */
	public boolean isInWrongPlace(StepIndex.WayIn way)
	{
		Entered entered = enteredBy(way);
		boolean elsewhere = entered != null && partWayFrom != null && partWayFrom.distanceTo2D(entered.from) > SAME_PLACE_TILES;
		return partWayFrom != null && (triedWrong || elsewhere);
	}

	/** How the player went in last time; null while it isn't known, or stored before the tile was kept whole. */
	public Entered enteredBy(StepIndex.WayIn way)
	{
		Entered entered = way == null ? null : remembered.get().entered.get(way.getText());
		return entered == null || entered.from == null ? null : entered;
	}

	/** By the way's text. */
	static final class Ways
	{
		Map<String, Entered> entered = new HashMap<>();

		Ways copy()
		{
			Ways copy = new Ways();
			copy.entered.putAll(entered);
			return copy;
		}
	}
}
