package com.cluehelper.player;

import lombok.Value;

@Value
public class ItemUse
{
	Container container;
	int itemId;
	/** 1 for an item that only has to be there, like a staff or a cape. */
	int quantity;

	@Override
	public String toString()
	{
		return quantity + "x" + itemId + "@" + container.name().toLowerCase();
	}
}
