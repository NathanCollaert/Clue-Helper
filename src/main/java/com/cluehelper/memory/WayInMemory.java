package com.cluehelper.memory;

import com.cluehelper.step.StepIndex;
import java.util.*;
import javax.inject.*;
import net.runelite.api.coords.WorldPoint;

/**
 * Client thread only. Where the player went into a way in entered at one of several places, learnt as they go in: the
 * Barrows crypt with the tunnel is only known once its sarcophagus is searched. Kept until the way's forget line shows
 * in chat (the chest's count once it is looted).
 */
@Singleton
public class WayInMemory
{
	final Map<String, WorldPoint> entered = new HashMap<>();
	WorldPoint last;
	/** The tile the player last stepped from onto the way's part-way floors (a crypt), for the way being watched. */
	WorldPoint partWayFrom;

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
		}
		else if (way.isInside(at) && way.isPartWay(before) && partWayFrom != null)
		{
			entered.put(way.getText(), partWayFrom);
		}
	}

	public void onGameLine(StepIndex.WayIn way, String line)
	{
		if (way != null && way.getForgetOn() != null && line.contains(way.getForgetOn()))
		{
			entered.remove(way.getText());
		}
	}

	/** Where the player went in last time; null while it isn't known. */
	public WorldPoint enteredAt(StepIndex.WayIn way)
	{
		return way == null ? null : entered.get(way.getText());
	}
}
