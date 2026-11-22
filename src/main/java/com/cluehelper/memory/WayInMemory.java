package com.cluehelper.memory;

import com.cluehelper.step.StepIndex;
import java.util.*;
import javax.inject.*;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Client thread only. Where the player went into a way in entered at one of several places, learnt as they go in: the
 * Barrows crypt with the tunnel is only known once its sarcophagus is searched. Kept across sessions until the way's
 * forget line shows in chat (the chest's count once it is looted).
 */
@Singleton
public class WayInMemory
{
	static final String KEY = "waysIn";

	/** Where the player stepped onto the way's part-way floors, and the object they then went in by. */
	@Value
	public static class Entered
	{
		WorldPoint from;
		int object;
		String option;
	}

	MemorySlot<Ways> remembered;
	WorldPoint last;
	/** The tile the player last stepped from onto the way's part-way floors (a crypt), for the way being watched. */
	WorldPoint partWayFrom;
	/** The object last clicked on the part-way floors (the sarcophagus searched); -1 for none. */
	int clicked = -1;
	String clickedOption;

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
			clicked = -1;
		}
		else if (way.isInside(at) && way.isPartWay(before) && partWayFrom != null)
		{
			Ways ways = remembered.get().copy();
			ways.entered.put(way.getText(), new Place(partWayFrom, clicked, clickedOption, way.getForgetOn()));
			remembered.set(ways);
		}
	}

	/** An object clicked while on the way's part-way floors: the last one before going in is how the player went in. */
	public void onObjectClicked(StepIndex.WayIn way, WorldPoint at, int objectId, String option)
	{
		if (way != null && way.isRemembered() && at != null && way.isPartWay(at))
		{
			clicked = objectId;
			clickedOption = option;
		}
	}

	/** Whatever the step: the Barrows chest looted on any trip moves the tunnel. */
	public void onGameLine(String line)
	{
		Ways ways = remembered.get().copy();
		if (ways.entered.values().removeIf(place -> place.forgetOn != null && line.contains(place.forgetOn)))
		{
			remembered.set(ways);
		}
	}

	/** How the player went in last time; null while it isn't known. */
	public Entered enteredBy(StepIndex.WayIn way)
	{
		Place place = way == null ? null : remembered.get().entered.get(way.getText());
		return place == null ? null : new Entered(new WorldPoint(place.x, place.y, place.plane), place.object, place.option);
	}

	/** Stored as plain numbers, by the way's text. */
	static final class Ways
	{
		Map<String, Place> entered = new HashMap<>();

		Ways copy()
		{
			Ways copy = new Ways();
			copy.entered.putAll(entered);
			return copy;
		}
	}

	static final class Place
	{
		int x;
		int y;
		int plane;
		int object;
		String option;
		/** The way's forget line, kept with the place so it is forgotten whatever step is on. */
		String forgetOn;

		Place()
		{
		}

		Place(WorldPoint tile, int object, String option, String forgetOn)
		{
			x = tile.getX();
			y = tile.getY();
			plane = tile.getPlane();
			this.object = object;
			this.option = option;
			this.forgetOn = forgetOn;
		}
	}
}
