package com.cluehelper.guide;

import com.cluehelper.route.BookScrolls;
import com.cluehelper.transport.CatalogRow;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID.Bookofscrolls;

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
		new Scroll("Ardeaglais teleport", Bookofscrolls.TELEPORTSCROLL_ARDEAGLAIS, Bookofscrolls.GFX_ARDEAGLAIS, Bookofscrolls.TEXT_ARDEAGLAIS, Bookofscrolls.TITLE_ARDEAGLAIS),
		new Scroll("Chasm teleport scroll", Bookofscrolls.TELEPORTSCROLL_CHASMOFFIRE, Bookofscrolls.GFX_CHASMOFFIRE, Bookofscrolls.TEXT_CHASMOFFIRE, Bookofscrolls.TITLE_CHASMOFFIRE),
		new Scroll("Colossal wyrm teleport scroll", Bookofscrolls.TELEPORTSCROLL_COLOSSAL_WYRM, Bookofscrolls.GFX_COLOSSAL_WYRM, Bookofscrolls.TEXT_COLOSSAL_WYRM, Bookofscrolls.TITLE_COLOSSAL_WYRM),
		new Scroll("Digsite teleport", Bookofscrolls.TELEPORTSCROLL_DIGSITE, Bookofscrolls.GFX_DIGSITE, Bookofscrolls.TEXT_DIGSITE, Bookofscrolls.TITLE_DIGSITE),
		new Scroll("Feldip hills teleport", Bookofscrolls.TELEPORTSCROLL_FELDIP, Bookofscrolls.GFX_FELDIP, Bookofscrolls.TEXT_FELDIP, Bookofscrolls.TITLE_FELDIP),
		new Scroll("Guthixian temple teleport", Bookofscrolls.TELEPORTSCROLL_GUTHIXIAN_TEMPLE, Bookofscrolls.GFX_GUTHIXIAN_TEMPLE, Bookofscrolls.TEXT_GUTHIXIAN_TEMPLE, Bookofscrolls.TITLE_GUTHIXIAN_TEMPLE),
		new Scroll("Iorwerth camp teleport", Bookofscrolls.TELEPORTSCROLL_ELF, Bookofscrolls.GFX_ELF, Bookofscrolls.TEXT_ELF, Bookofscrolls.TITLE_ELF),
		new Scroll("Key master teleport", Bookofscrolls.TELEPORTSCROLL_CERBERUS, Bookofscrolls.GFX_CERBERUS, Bookofscrolls.TEXT_CERBERUS, Bookofscrolls.TITLE_CERBERUS),
		new Scroll("Lumberyard teleport", Bookofscrolls.TELEPORTSCROLL_LUMBERYARD, Bookofscrolls.GFX_LUMBERYARD, Bookofscrolls.TEXT_LUMBERYARD, Bookofscrolls.TITLE_LUMBERYARD),
		new Scroll("Lunar isle teleport", Bookofscrolls.TELEPORTSCROLL_LUNARISLE, Bookofscrolls.GFX_LUNARISLE, Bookofscrolls.TEXT_LUNARISLE, Bookofscrolls.TITLE_LUNAR),
		new Scroll("Mort'ton teleport", Bookofscrolls.TELEPORTSCROLL_MORTTON, Bookofscrolls.GFX_MORTTON, Bookofscrolls.TEXT_MORTTON, Bookofscrolls.TITLE_MORTTON),
		new Scroll("Mos le'harmless teleport", Bookofscrolls.TELEPORTSCROLL_MOSLES, Bookofscrolls.GFX_MOSLES, Bookofscrolls.TEXT_MOSLES, Bookofscrolls.TITLE_MOSLES),
		new Scroll("Nardah teleport", Bookofscrolls.TELEPORTSCROLL_NARDAH, Bookofscrolls.GFX_NARDAH, Bookofscrolls.TEXT_NARDAH, Bookofscrolls.TITLE_NARDAH),
		new Scroll("Pest control teleport", Bookofscrolls.TELEPORTSCROLL_PESTCONTROL, Bookofscrolls.GFX_PESTCONTROL, Bookofscrolls.TEXT_PESTCONTROL, Bookofscrolls.TITLE_PESTCONTROL),
		new Scroll("Piscatoris teleport", Bookofscrolls.TELEPORTSCROLL_PISCATORIS, Bookofscrolls.GFX_PISCATORIS, Bookofscrolls.TEXT_PISCATORIS, Bookofscrolls.TITLE_PISCATORIS),
		new Scroll("Revenant cave teleport", Bookofscrolls.TELEPORTSCROLL_REVENANTS, Bookofscrolls.GFX_REVENANTS, Bookofscrolls.TEXT_REVENANTS, Bookofscrolls.TITLE_REVENANTS),
		new Scroll("Spider cave teleport", Bookofscrolls.TELEPORTSCROLL_SPIDERCAVE, Bookofscrolls.GFX_SPIDERCAVE, Bookofscrolls.TEXT_SPIDERCAVE, Bookofscrolls.TITLE_SPIDERCAVE),
		new Scroll("Tai bwo wannai teleport", Bookofscrolls.TELEPORTSCROLL_TAIBWO, Bookofscrolls.GFX_TAIBWO, Bookofscrolls.TEXT_TAIBWO, Bookofscrolls.TITLE_TAIBWO),
		new Scroll("Watson teleport", Bookofscrolls.TELEPORTSCROLL_WATSON, Bookofscrolls.GFX_WATSON, Bookofscrolls.TEXT_WATSON, Bookofscrolls.TITLE_WATSON),
		new Scroll("Zul-andra teleport", Bookofscrolls.TELEPORTSCROLL_ZULANDRA, Bookofscrolls.GFX_ZULANDRA, Bookofscrolls.TEXT_ZULANDRA, Bookofscrolls.TITLE_ZULANDRA));
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
		return carriesBook && wildernessTarget ? withBookDeposited(steps) : steps;
	}

	/** Walking into the Wilderness: the plan's bank stop takes the book. */
	private static List<SubStep> withBookDeposited(List<SubStep> steps)
	{
		List<SubStep> out = new ArrayList<>(steps);
		int bank = lastBefore(out, out.size(), SubStep.Kind.BANK);
		if (bank >= 0)
		{
			out.add(bank + 1, deposit());
		}
		else
		{
			out.addAll(0, List.of(bankAgain(null), deposit(), closeBank()));
		}
		return List.copyOf(out);
	}

	private static List<SubStep> bookBanked(List<SubStep> steps, int teleportAt, boolean bookRow)
	{
		List<SubStep> out = new ArrayList<>(steps);
		SubStep teleport = out.get(teleportAt);
		String scroll = bookRow ? BookScrolls.scrollName(teleport.getRow()) : null;
		if (bookRow)
		{
			out.set(teleportAt, teleport.toBuilder().text("Read the " + scroll + " scroll").actItemNamed(scroll.toLowerCase())
				.actOption(READ_OPTION).build());
		}
		int bank = lastBefore(out, teleportAt, SubStep.Kind.BANK);
		int withdraw = lastBefore(out, teleportAt, SubStep.Kind.WITHDRAW);
		boolean bookFromBank = withdraw >= 0 && out.get(withdraw).getBankItems().contains(BookScrolls.BOOK);
		WorldPoint bankTile = bank >= 0 ? out.get(bank).getWaypoint() : null;
		if (bookRow && (bookFromBank || bank < 0))
		{
			out.addAll(withdraw >= 0 ? withdraw + 1 : 0, List.of(takeOut(teleport.getRow()), bankAgain(bankTile), deposit(), closeBank()));
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
			out.add(bank, takeOut(teleport.getRow()));
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
		return SubStep.builder().kind(SubStep.Kind.ACT).text("Open the master scroll book: take out a " + scroll)
			.detail("With the bank closed").row(row).actItemNamed(BOOK_NAME).actOption(OPEN_OPTION)
			.carriedWhenDone(scroll.toLowerCase()).done(SubStep.Done.ITEM_CARRIED).build();
	}

	private static SubStep bankAgain(WorldPoint bank)
	{
		return SubStep.builder().kind(SubStep.Kind.BANK).text("Open the bank").detail("To deposit the master scroll book")
			.waypoint(bank).build();
	}

	private static SubStep deposit()
	{
		return SubStep.builder().kind(SubStep.Kind.ACT).text("Deposit the master scroll book").detail("It stays out of the Wilderness")
			.actItemNamed(BOOK_NAME).done(SubStep.Done.ITEM_GONE).build();
	}

	private static SubStep closeBank()
	{
		return SubStep.builder().kind(SubStep.Kind.WITHDRAW).text("Close the bank").build();
	}
}
