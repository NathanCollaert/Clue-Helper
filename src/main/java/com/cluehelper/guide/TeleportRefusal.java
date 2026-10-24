package com.cluehelper.guide;

import net.runelite.api.coords.WorldPoint;

/** Client thread only. A game message right after a teleport click, with no animation, means the game refused it. */
final class TeleportRefusal
{
	/** A teleport animates on its click's tick or within two: three ticks without one means it was refused. */
	private static final int CHECK_TICKS = 3;

	private int clickTick = ActWatcher.NEVER;
	private WorldPoint clickTile;
	private String row;
	private int checkTick = ActWatcher.NEVER;

	void clicked(int tick, WorldPoint tile)
	{
		clickTick = tick;
		clickTile = tile;
	}

	/** A game line within a tick of the click: the row is suspect until its check tick. */
	void onGameLine(String rowName, int tick)
	{
		if (clickTick != ActWatcher.NEVER && tick - clickTick <= 1)
		{
			row = rowName;
			checkTick = tick + CHECK_TICKS;
		}
	}

	/**
	 * The row the game refused, once its check tick has come while the step is still its teleport and the player stands
	 * by the click's tile without animating; null otherwise.
	 */
	String refused(int tick, String currentRow, WorldPoint tile, boolean standingStill)
	{
		if (row == null || tick < checkTick)
		{
			return null;
		}
		boolean sameStep = row.equals(currentRow);
		boolean stayed = tile != null && clickTile != null && SubSteps.near(tile, clickTile, StepGuide.JUMP_TILES);
		String refused = sameStep && stayed && standingStill ? row : null;
		row = null;
		clickTick = ActWatcher.NEVER;
		return refused;
	}

	void clear()
	{
		row = null;
		clickTick = ActWatcher.NEVER;
	}
}
