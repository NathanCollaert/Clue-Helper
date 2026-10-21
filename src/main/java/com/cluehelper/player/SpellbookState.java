package com.cluehelper.player;

import java.util.EnumSet;
import java.util.Set;
import lombok.Value;

@Value
public class SpellbookState
{
	public static final int CAPE_CHANGES_PER_DAY = 5;

	/** Already the temporary book during a Spellbook Swap. */
	Book book;
	/** A Spellbook Swap reverts on the next cast, a cape change, or after 200 ticks. */
	boolean pendingRevert;
	/** One daily counter shared by the Magic, Max and mounted Max cape. Read live, never stored. */
	int capeChangesLeft;
	boolean arceuusUnlocked;
	boolean ancientAccess;
	boolean lunarAccess;
	boolean swapAccess;

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
