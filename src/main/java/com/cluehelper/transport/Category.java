package com.cluehelper.transport;

import java.util.Locale;

public enum Category
{
	SPELL, HOME, MINIGAME, FAIRY_RING, TABLET, SCROLL, JEWELLERY, DIARY, CAPE, ITEM,
	// House furniture, used from the POH node
	NEXUS, JEWELLERY_BOX, MOUNTED, PORTAL, OBELISK,
	/** In the house garden and out in the world. */
	SPIRIT_TREE,
	/** Renu at every quetzal site; the whistle is an ITEM row. */
	QUETZAL;

	static Category of(String cell)
	{
		return valueOf(cell.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
	}
}
