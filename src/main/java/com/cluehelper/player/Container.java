package com.cluehelper.player;

public enum Container
{
	INVENTORY, WORN, RUNE_POUCH, BANK, GROUP_STORAGE;

	/** On the player: in the inventory or worn. */
	public boolean isCarried()
	{
		return this == INVENTORY || this == WORN;
	}

	/** In the bank or the group storage, so a bank stop takes it out. */
	public boolean isStored()
	{
		return this == BANK || this == GROUP_STORAGE;
	}
}
