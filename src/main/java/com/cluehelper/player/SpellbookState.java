package com.cluehelper.player;

import java.util.EnumSet;
import java.util.Set;
import lombok.Value;

/** The spellbook vars B04 confirmed live. */
@Value
public class SpellbookState
{
	public static final int CAPE_CHANGES_PER_DAY = 5;

	/** Varbit 4070: the book spells can be cast from, already the temporary book during a Spellbook Swap. */
	Book book;
	/** Varbit 3617 = 1: a Spellbook Swap will revert on the next cast, a cape change, or after 200 ticks. */
	boolean pendingRevert;
	/** 5 − varbit 4816, one counter shared by the Magic, Max and mounted Max cape. Read live, never stored. */
	int capeChangesLeft;
	/** Varbit 9631 (varp 2071 bit 17). */
	boolean arceuusUnlocked;
	/** Desert Treasure I. */
	boolean ancientAccess;
	/** Lunar Diplomacy. */
	boolean lunarAccess;
	/** Dream Mentor. */
	boolean swapAccess;

	/** Standard and every book the player has access to: where the cape or the occult altar can go. */
	public Set<Book> usableBooks()
	{
		Set<Book> books = EnumSet.of(Book.STANDARD);
		if (ancientAccess)
		{
			books.add(Book.ANCIENT);
		}
		if (lunarAccess)
		{
			books.add(Book.LUNAR);
		}
		if (arceuusUnlocked)
		{
			books.add(Book.ARCEUUS);
		}
		return books;
	}
}
