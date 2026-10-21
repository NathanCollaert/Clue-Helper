package com.cluehelper.transport;

import java.util.Locale;

/** What kind of teleport a catalog row is: the catalog's Category column. */
public enum Category
{
	SPELL, HOME, MINIGAME, FAIRY_RING, TABLET, SCROLL, JEWELLERY, DIARY, CAPE, ITEM,
	/** House furniture (B16), used from the POH node. */
	NEXUS, JEWELLERY_BOX, MOUNTED, PORTAL, OBELISK,
	/** In the house garden and out in the world (B17). */
	SPIRIT_TREE,
	/** Renu at every quetzal site (B17); the whistle is an ITEM row. */
	QUETZAL;

	static Category of(String cell)
	{
		return valueOf(cell.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
	}
}
