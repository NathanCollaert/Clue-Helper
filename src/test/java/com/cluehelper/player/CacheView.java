package com.cluehelper.player;

import java.util.List;

public interface CacheView
{
	int[] enumKeys(int enumId);

	/** In the same order as {@link #enumKeys}. */
	int[] enumIntValues(int enumId);

	int itemIntParam(int itemId, int param, int absent);

	/** Null when the item doesn't carry the param. */
	String itemStringParam(int itemId, int param);

	String itemName(int itemId);

	/** Each row is {required rune, rune that also counts as it}. */
	List<int[]> multirunes();
}
