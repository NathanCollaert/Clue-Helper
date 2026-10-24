package com.cluehelper.guide;

import com.cluehelper.player.Book;

/** Client thread only. A refused book change leaves the book as it was; the next tick shows whether it changed. */
final class ChangeRefusal
{
	private static final int CHECK_TICKS = 2;

	private String candidate;
	private Book book;
	private int checkTick;

	/** A MESBOX line during the change: the change is suspect until its check tick. */
	void suspect(String change, Book book, int tick)
	{
		candidate = change;
		this.book = book;
		checkTick = tick + CHECK_TICKS;
	}

	/** The change the game refused, once its check tick has come and the book is not the one it changes to; null otherwise. */
	String refused(int tick, int spellbookVarbit)
	{
		if (candidate == null || tick < checkTick)
		{
			return null;
		}
		String refused = spellbookVarbit != book.ordinal() ? candidate : null;
		candidate = null;
		return refused;
	}

	void clear()
	{
		candidate = null;
	}
}
