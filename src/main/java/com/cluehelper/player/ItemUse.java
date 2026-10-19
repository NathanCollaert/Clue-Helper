package com.cluehelper.player;

import lombok.Value;

/** One held item that satisfies part of a requirement: a witness entry. */
@Value
public class ItemUse
{
	Container container;
	int itemId;
	/** How many the row uses up, or 1 for an item that only has to be there (a staff, a cape). */
	int quantity;

	@Override
	public String toString()
	{
		return quantity + "x" + itemId + "@" + container.name().toLowerCase();
	}
}
