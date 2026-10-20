package com.cluehelper.transport;

import java.util.Locale;

/** What kind of teleport a catalog row is: the catalog's Category column. */
public enum Category
{
	SPELL, HOME, MINIGAME, FAIRY_RING, TABLET, SCROLL, JEWELLERY, DIARY, CAPE, ITEM,
	/** House furniture (B16): every one of these starts at the POH node. */
	NEXUS, JEWELLERY_BOX, MOUNTED, PORTAL, SPIRIT_TREE, OBELISK;

	/** Furniture inside the player's house, used from the POH node. */
	public boolean isHouse()
	{
		return ordinal() >= NEXUS.ordinal();
	}

	static Category of(String cell)
	{
		return valueOf(cell.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
	}
}
