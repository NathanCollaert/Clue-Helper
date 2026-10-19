package com.cluehelper.transport;

import java.util.Locale;

/** What kind of teleport a catalog row is: the catalog's Category column. */
public enum Category
{
	SPELL, HOME, MINIGAME, FAIRY_RING, TABLET, SCROLL, JEWELLERY, DIARY, CAPE, ITEM;

	static Category of(String cell)
	{
		return valueOf(cell.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
	}
}
