package com.cluehelper.route;

import com.cluehelper.player.PositionRules;
import com.cluehelper.transport.CatalogRow;
import net.runelite.api.gameval.ItemID;

/**
 * The master scroll book need not be risked in the Wilderness: a scroll for a Wilderness landing (Revenant caves) is
 * taken out of it, and the book banked, before the teleport; any teleport into the Wilderness banks a carried book.
 * In the bank interface a click on an inventory item deposits it, so the scroll comes out with the bank closed.
 */
public final class BookScrolls
{
	public static final String BOOK_ROW_PREFIX = "Master scroll book: ";
	public static final int BOOK = ItemID.BOOKOFSCROLLS_CHARGED;
	/** Close the bank, open the book, take the scroll out, open the bank again, deposit the book. */
	static final int TAKE_OUT_TICKS = 6;
	static final int DEPOSIT_TICKS = 1;

	private BookScrolls()
	{
	}

	public static boolean isTakenOutAtBank(CatalogRow row, boolean bookStaysOut)
	{
		return bookStaysOut && row.getName().startsWith(BOOK_ROW_PREFIX) && intoWilderness(row);
	}

	public static boolean intoWilderness(CatalogRow row)
	{
		return PositionRules.wildernessLevel(row.getDestination()) > 0;
	}

	/** "Revenant cave teleport", as the loose scroll is named. */
	public static String scrollName(CatalogRow row)
	{
		return row.getName().substring(BOOK_ROW_PREFIX.length());
	}
}
