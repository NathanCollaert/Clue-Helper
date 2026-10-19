package com.cluehelper.player;

import lombok.Value;

/** What is known about one item's charges (PLAN §4 item 4). */
@Value
public class Charge
{
	/** Where the count came from, in the order they are tried. */
	public enum Source
	{
		/** The item id encodes it: glory(4), ring of dueling(8). */
		ITEM_ID,
		/** The item's charge varbit, read while the item is held. */
		VARBIT,
		/** The last value seen while held, remembered per profile. */
		REMEMBERED,
		/** RuneLite's Item Charges plugin, read only. */
		ITEM_CHARGES_PLUGIN
	}

	int charges;
	Source source;
}
