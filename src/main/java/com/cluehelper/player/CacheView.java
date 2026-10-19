package com.cluehelper.player;

import java.util.List;

/** The few cache tables the rune model reads (B04 step 9). Live it is the client; in tests a cache dump. */
public interface CacheView
{
	/** Empty when the enum doesn't exist. */
	int[] enumKeys(int enumId);

	/** In the same order as {@link #enumKeys}. */
	int[] enumIntValues(int enumId);

	/** {@code absent} when the item doesn't carry the param. */
	int itemIntParam(int itemId, int param, int absent);

	/** Null when the item doesn't carry the param. */
	String itemStringParam(int itemId, int param);

	String itemName(int itemId);

	/** The rows of DB table 91 as {required rune, rune that also counts as it}. */
	List<int[]> multirunes();
}
