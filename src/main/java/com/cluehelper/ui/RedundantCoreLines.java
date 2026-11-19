package com.cluehelper.ui;

import java.util.List;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.*;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;

/**
 * Core's hint lines that the Clue Helper route makes needless: how to find the spot (click the clue on the world map,
 * travel there and dig). A LineComponent has no getter for its text, so the lines are found by where core puts them:
 * <ul>
 * <li>map clue: title, world-map line, then the object to search or the travel line, then a blank line and the
 * description when it has one;</li>
 * <li>coordinate: title, the directions and a blank line when it has directions, world-map line;</li>
 * <li>fairy ring: title, "Code:", the code, travel line.</li>
 * </ul>
 */
final class RedundantCoreLines
{
	private static final int[] NONE = {};
	private static final int[] UNDER_TITLE = {1};
	private static final int[] MAP_WITHOUT_OBJECT = {2, 1};
	private static final int[] MAP_WITHOUT_OBJECT_WITH_DESCRIPTION = {3, 2, 1};
	private static final int[] COORDINATE_WITH_DIRECTIONS = {3, 2};
	private static final int[] FAIRY_RING = {3};
	private static final int NO_OBJECT = -1;

	private RedundantCoreLines()
	{
	}

	/** @param coreLines the clue's hint, top line first */
	static void remove(List<LayoutableRenderableEntity> coreLines, ClueScroll clue, ClueScrollPlugin plugin)
	{
		if (clue == null)
		{
			return;
		}
		for (int index : wayLinesHighestFirst(clue, plugin))
		{
			if (index < coreLines.size())
			{
				coreLines.remove(index);
			}
		}
	}



	private static int[] wayLinesHighestFirst(ClueScroll clue, ClueScrollPlugin plugin)
	{
		if (clue instanceof MapClue)
		{
			return mapClue((MapClue) clue);
		}
		if (clue instanceof CoordinateClue)
		{
			return hasDirections((CoordinateClue) clue, plugin) ? COORDINATE_WITH_DIRECTIONS : UNDER_TITLE;
		}
		return clue instanceof FairyRingClue ? FAIRY_RING : NONE;
	}

	/** The blank above the description goes with the travel line, or it would sit right under the title. */
	private static int[] mapClue(MapClue clue)
	{
		if (clue.getObjectId() != NO_OBJECT)
		{
			return UNDER_TITLE;
		}
		return clue.getDescription() == null ? MAP_WITHOUT_OBJECT : MAP_WITHOUT_OBJECT_WITH_DESCRIPTION;
	}

	private static boolean hasDirections(CoordinateClue clue, ClueScrollPlugin plugin)
	{
		return CoordinateClue.forLocation(clue.getLocation(plugin)) != null;
	}
}
