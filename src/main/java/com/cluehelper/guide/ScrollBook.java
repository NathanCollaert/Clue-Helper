package com.cluehelper.guide;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Value;
import net.runelite.api.gameval.InterfaceID.Bookofscrolls;

public final class ScrollBook
{
	private static final String ROW_PREFIX = "Master scroll book: ";
	public static final int NO_WIDGET = -1;

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
}
