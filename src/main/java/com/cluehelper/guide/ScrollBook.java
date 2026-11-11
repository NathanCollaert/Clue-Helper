package com.cluehelper.guide;

import com.cluehelper.Tables;
import com.cluehelper.route.BookScrolls;
import com.cluehelper.transport.CatalogRow;
import java.util.*;
import java.util.function.Function;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;
import static java.util.stream.Collectors.*;
import com.cluehelper.guide.SubStep.Kind;

/** The master scroll book: its interface's widget per scroll, and the bank stops that keep it out of the Wilderness. */
public final class ScrollBook
{
	private static final String ROW_PREFIX = "Master scroll book: ";
	public static final int NO_WIDGET = -1;
	static final String BOOK_NAME = "master scroll book";
	static final String REMOVE_OPTION = "Remove";
	private static final String OPEN_OPTION = "Open";
	private static final String READ_OPTION = "Read";

	/** The book's widgets per scroll: later scrolls don't sit in entry, picture, charges, name order. */
	@Value
	private static class Scroll
	{
		String name;
		int entry;
		int picture;
		int charges;
		int title;
	}

	private static final List<Scroll> SCROLLS = Tables.rows("scrolls").stream()
		.map(row -> new Scroll(row.getKey(), row.id(0), row.id(1), row.id(2), row.id(3))).collect(toUnmodifiableList());
	private static final Map<String, Scroll> BY_NAME = SCROLLS.stream().collect(toUnmodifiableMap(Scroll::getName, Function.identity()));
	private static final Map<Integer, Scroll> BY_ENTRY = SCROLLS.stream().collect(toUnmodifiableMap(Scroll::getEntry, Function.identity()));

	private ScrollBook()
	{
	}

	public static int picture(int entry)
	{
		Scroll scroll = BY_ENTRY.get(entry);
		return scroll == null ? NO_WIDGET : scroll.getPicture();
	}

	static int[] texts(int entry)
	{
		Scroll scroll = BY_ENTRY.get(entry);
		return scroll == null ? new int[0] : new int[]{scroll.getCharges(), scroll.getTitle()};
	}

	/** The scroll's entry, picture, charges and name, whose menus all offer its options. */
	static Set<Integer> widgets(String rowName)
	{
		int entry = entry(rowName);
		Scroll scroll = BY_ENTRY.get(entry);
		return scroll == null ? Set.of() : Set.of(scroll.getEntry(), scroll.getPicture(), scroll.getCharges(), scroll.getTitle());
	}

	static int entry(String rowName)
	{
		Scroll scroll = rowName.startsWith(ROW_PREFIX) ? BY_NAME.get(rowName.substring(ROW_PREFIX.length())) : null;
		return scroll == null ? NO_WIDGET : scroll.getEntry();
	}

	/**
	 * The master scroll book stays out of the Wilderness. Its own teleport there reads the loose scroll, taken out with the
	 * bank closed (a click in the bank interface deposits), then the book is deposited; any other teleport there banks a
	 * carried book. Each sub-step is done by what is carried and whether the bank is open, so a new plan picks up where
	 * the player is.
	 */
	static List<SubStep> withBookBanked(List<SubStep> steps, boolean carriesBook, boolean wildernessTarget)
	{
		for (int i = 0; i < steps.size(); i++)
		{
			SubStep teleport = steps.get(i);
			CatalogRow row = teleport.getKind() == Kind.TELEPORT ? teleport.getRow() : null;
			boolean bookRow = row != null && BookScrolls.isTakenOutAtBank(row, true);
			if (row != null && (bookRow || carriesBook && BookScrolls.intoWilderness(row)))
			{
				return bookBanked(steps, i, bookRow);
			}
		}
		return carriesBook && wildernessTarget ? bookBanked(steps, steps.size(), false) : steps;
	}

	/**
	 * The book banked before the teleport at {@code teleportAt}. Walking into the Wilderness, {@code teleportAt} is the
	 * route's end: the plan's bank stop takes the book.
	 */
	private static List<SubStep> bookBanked(List<SubStep> steps, int teleportAt, boolean bookRow)
	{
		List<SubStep> out = new ArrayList<>(steps);
		CatalogRow row = bookRow ? out.get(teleportAt).getRow() : null;
		if (bookRow)
		{
			String scroll = BookScrolls.scrollName(row);
			out.set(teleportAt, out.get(teleportAt).toBuilder().text("Read the " + scroll + " scroll").actItemNamed(scroll.toLowerCase())
				.actOption(READ_OPTION).build());
		}
		int bank = lastBefore(out, teleportAt, Kind.BANK);
		int withdraw = lastBefore(out, teleportAt, Kind.WITHDRAW);
		boolean bookFromBank = withdraw >= 0 && out.get(withdraw).getBankItems().contains(BookScrolls.BOOK);
		WorldPoint bankTile = bank >= 0 ? out.get(bank).getWaypoint() : null;
		if (bookRow && (bookFromBank || bank < 0))
		{
			out.addAll(withdraw >= 0 ? withdraw + 1 : 0, List.of(takeOut(row), bankAgain(bankTile), deposit(), closeBank()));
			return List.copyOf(out);
		}
		if (bank < 0)
		{
			out.addAll(0, List.of(bankAgain(null), deposit(), closeBank()));
			return List.copyOf(out);
		}
		out.add(bank + 1, deposit());
		if (bookRow)
		{
			out.add(bank, takeOut(row));
		}
		return List.copyOf(out);
	}

	private static int lastBefore(List<SubStep> steps, int before, Kind kind)
	{
		for (int i = before - 1; i >= 0; i--)
		{
			if (steps.get(i).getKind() == kind)
			{
				return i;
			}
		}
		return -1;
	}

	/** Opens the book, then Remove on the scroll's entry; {@code row} is the book's teleport. */
	private static SubStep takeOut(CatalogRow row)
	{
		String scroll = BookScrolls.scrollName(row);
		return SubStep.of(Kind.ACT, "Open the master scroll book: take out a " + scroll)
			.detail("With the bank closed").row(row).actItemNamed(BOOK_NAME).actOption(OPEN_OPTION)
			.carriedWhenDone(scroll.toLowerCase()).done(SubStep.Done.ITEM_CARRIED).build();
	}

	private static SubStep bankAgain(WorldPoint bank)
	{
		return SubStep.of(Kind.BANK, "Open the bank").detail("To deposit the master scroll book")
			.waypoint(bank).build();
	}

	private static SubStep deposit()
	{
		return SubStep.of(Kind.ACT, "Deposit the master scroll book").detail("It stays out of the Wilderness")
			.actItemNamed(BOOK_NAME).done(SubStep.Done.ITEM_GONE).build();
	}

	private static SubStep closeBank()
	{
		return SubStep.of(Kind.WITHDRAW, "Close the bank").build();
	}
}
