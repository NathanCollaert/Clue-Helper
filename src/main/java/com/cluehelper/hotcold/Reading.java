package com.cluehelper.hotcold;

import java.util.List;
import lombok.*;
import net.runelite.api.coords.WorldPoint;

/** Stored as JSON in the feel log, so its fields are its format. */
@Getter
@EqualsAndHashCode
@ToString
@AllArgsConstructor
@NoArgsConstructor
public final class Reading
{
	private int x;
	private int y;
	private int plane;
	/** Null on an anchor: a finished step's last reading, kept only for the next step's first comparison. */
	private Band band;
	/** Null on a step's first reading and on the shaking line. */
	private Comparison comparison;

	public Reading asAnchor()
	{
		return new Reading(x, y, plane, null, null);
	}

	public boolean isAnchor()
	{
		return band == null;
	}

	/**
	 * Core compares Prifddinas through its overworld mirror; whether the device does is unmeasured, so a reading there
	 * fits a spot from either tile.
	 */
	public List<WorldPoint> tiles()
	{
		WorldPoint tile = new WorldPoint(x, y, plane);
		WorldPoint mirror = WorldPoint.getMirrorPoint(tile, true);
		return mirror.equals(tile) ? List.of(tile) : List.of(tile, mirror);
	}
}
