package com.cluehelper.guide;

import com.cluehelper.route.BookScrolls;
import com.cluehelper.transport.CatalogRow;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;
import static net.runelite.api.gameval.InterfaceID.Bookofscrolls.*;

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

	private static final List<Scroll> SCROLLS = List.of(
		new Scroll("Ardeaglais teleport", TELEPORTSCROLL_ARDEAGLAIS, GFX_ARDEAGLAIS, TEXT_ARDEAGLAIS, TITLE_ARDEAGLAIS),
		new Scroll("Chasm teleport scroll", TELEPORTSCROLL_CHASMOFFIRE, GFX_CHASMOFFIRE, TEXT_CHASMOFFIRE, TITLE_CHASMOFFIRE),
		new Scroll("Colossal wyrm teleport scroll", TELEPORTSCROLL_COLOSSAL_WYRM, GFX_COLOSSAL_WYRM, TEXT_COLOSSAL_WYRM, TITLE_COLOSSAL_WYRM),
		new Scroll("Digsite teleport", TELEPORTSCROLL_DIGSITE, GFX_DIGSITE, TEXT_DIGSITE, TITLE_DIGSITE),
		new Scroll("Feldip hills teleport", TELEPORTSCROLL_FELDIP, GFX_FELDIP, TEXT_FELDIP, TITLE_FELDIP),
		new Scroll("Guthixian temple teleport", TELEPORTSCROLL_GUTHIXIAN_TEMPLE, GFX_GUTHIXIAN_TEMPLE, TEXT_GUTHIXIAN_TEMPLE, TITLE_GUTHIXIAN_TEMPLE),
		new Scroll("Iorwerth camp teleport", TELEPORTSCROLL_ELF, GFX_ELF, TEXT_ELF, TITLE_ELF),
		new Scroll("Key master teleport", TELEPORTSCROLL_CERBERUS, GFX_CERBERUS, TEXT_CERBERUS, TITLE_CERBERUS),
		new Scroll("Lumberyard teleport", TELEPORTSCROLL_LUMBERYARD, GFX_LUMBERYARD, TEXT_LUMBERYARD, TITLE_LUMBERYARD),
		new Scroll("Lunar isle teleport", TELEPORTSCROLL_LUNARISLE, GFX_LUNARISLE, TEXT_LUNARISLE, TITLE_LUNAR),
		new Scroll("Mort'ton teleport", TELEPORTSCROLL_MORTTON, GFX_MORTTON, TEXT_MORTTON, TITLE_MORTTON),
		new Scroll("Mos le'harmless teleport", TELEPORTSCROLL_MOSLES, GFX_MOSLES, TEXT_MOSLES, TITLE_MOSLES),
		new Scroll("Nardah teleport", TELEPORTSCROLL_NARDAH, GFX_NARDAH, TEXT_NARDAH, TITLE_NARDAH),
		new Scroll("Pest control teleport", TELEPORTSCROLL_PESTCONTROL, GFX_PESTCONTROL, TEXT_PESTCONTROL, TITLE_PESTCONTROL),
		new Scroll("Piscatoris teleport", TELEPORTSCROLL_PISCATORIS, GFX_PISCATORIS, TEXT_PISCATORIS, TITLE_PISCATORIS),
		new Scroll("Revenant cave teleport", TELEPORTSCROLL_REVENANTS, GFX_REVENANTS, TEXT_REVENANTS, TITLE_REVENANTS),
		new Scroll("Spider cave teleport", TELEPORTSCROLL_SPIDERCAVE, GFX_SPIDERCAVE, TEXT_SPIDERCAVE, TITLE_SPIDERCAVE),
		new Scroll("Tai bwo wannai teleport", TELEPORTSCROLL_TAIBWO, GFX_TAIBWO, TEXT_TAIBWO, TITLE_TAIBWO),
		new Scroll("Watson teleport", TELEPORTSCROLL_WATSON, GFX_WATSON, TEXT_WATSON, TITLE_WATSON),
		new Scroll("Zul-andra teleport", TELEPORTSCROLL_ZULANDRA, GFX_ZULANDRA, TEXT_ZULANDRA, TITLE_ZULANDRA));
	private static final Map<String, Scroll> BY_NAME = SCROLLS.stream().collect(Collectors.toUnmodifiableMap(Scroll::getName, Function.identity()));
	private static final Map<Integer, Scroll> BY_ENTRY = SCROLLS.stream().collect(Collectors.toUnmodifiableMap(Scroll::getEntry, Function.identity()));

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
			CatalogRow row = teleport.getKind() == SubStep.Kind.TELEPORT ? teleport.getRow() : null;
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
		int bank = lastBefore(out, teleportAt, SubStep.Kind.BANK);
		int withdraw = lastBefore(out, teleportAt, SubStep.Kind.WITHDRAW);
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

	private static int lastBefore(List<SubStep> steps, int before, SubStep.Kind kind)
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
		return SubStep.of(SubStep.Kind.ACT, "Open the master scroll book: take out a " + scroll)
			.detail("With the bank closed").row(row).actItemNamed(BOOK_NAME).actOption(OPEN_OPTION)
			.carriedWhenDone(scroll.toLowerCase()).done(SubStep.Done.ITEM_CARRIED).build();
	}

	private static SubStep bankAgain(WorldPoint bank)
	{
		return SubStep.of(SubStep.Kind.BANK, "Open the bank").detail("To deposit the master scroll book")
			.waypoint(bank).build();
	}

	private static SubStep deposit()
	{
		return SubStep.of(SubStep.Kind.ACT, "Deposit the master scroll book").detail("It stays out of the Wilderness")
			.actItemNamed(BOOK_NAME).done(SubStep.Done.ITEM_GONE).build();
	}

	private static SubStep closeBank()
	{
		return SubStep.of(SubStep.Kind.WITHDRAW, "Close the bank").build();
	}
}
