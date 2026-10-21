package com.cluehelper.player;

import lombok.Value;

@Value
public class Charge
{
	public enum Source
	{
		/** Each charge count is its own item id, named like "Amulet of glory(4)". */
		ITEM_ID,
		VARBIT,
		REMEMBERED,
		CHECK_LINE,
		ITEM_CHARGES_PLUGIN
	}

	int charges;
	Source source;
}
