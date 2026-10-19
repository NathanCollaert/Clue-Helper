package com.cluehelper.guide;

import java.util.Map;
import net.runelite.api.gameval.InterfaceID;

/** The master scroll book's entry per catalog row ("Master scroll book: Nardah teleport"), to highlight inside the book. */
final class ScrollBook
{
	static final String ROW_PREFIX = "Master scroll book: ";
	static final int NO_WIDGET = -1;
	private static final Map<String, Integer> ENTRIES = Map.ofEntries(
		Map.entry("Digsite teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_DIGSITE),
		Map.entry("Feldip hills teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_FELDIP),
		Map.entry("Iorwerth camp teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_ELF),
		Map.entry("Key master teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_CERBERUS),
		Map.entry("Lumberyard teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_LUMBERYARD),
		Map.entry("Lunar isle teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_LUNARISLE),
		Map.entry("Mort'ton teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_MORTTON),
		Map.entry("Mos le'harmless teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_MOSLES),
		Map.entry("Nardah teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_NARDAH),
		Map.entry("Pest control teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_PESTCONTROL),
		Map.entry("Piscatoris teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_PISCATORIS),
		Map.entry("Revenant cave teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_REVENANTS),
		Map.entry("Tai bwo wannai teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_TAIBWO),
		Map.entry("Watson teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_WATSON),
		Map.entry("Zul-andra teleport", InterfaceID.Bookofscrolls.TELEPORTSCROLL_ZULANDRA));

	private ScrollBook()
	{
	}

	static int entry(String rowName)
	{
		return rowName.startsWith(ROW_PREFIX) ? ENTRIES.getOrDefault(rowName.substring(ROW_PREFIX.length()), NO_WIDGET) : NO_WIDGET;
	}
}
