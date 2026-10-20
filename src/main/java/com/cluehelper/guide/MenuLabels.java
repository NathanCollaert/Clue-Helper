package com.cluehelper.guide;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares a menu line's text with a catalog label (B16). Lines carry hotkeys, colour tags and aliases the catalog
 * doesn't: {@code <col=735a28>1</col>: Tree Gnome Village}, {@code [L] Marim (Ape Atoll)}, {@code 1 :  Barrows}.
 * Only for lines no script argument identifies (right-click options, 187 and chatbox lists): the nexus and the
 * jewellery box are matched by struct id and index, never by text (better-teleport-menu rewrites them).
 */
public final class MenuLabels
{
	/** Options that open the teleport list rather than naming a destination. */
	static final Set<String> OPENERS = Set.of("rub", "teleport", "teleports", "teleport menu", "operate", "travel", "tree",
		"activate", "configure", "ring configure", "perks", "spellbook", "commune");
	/**
	 * Catalog labels (Shortest Path's names) whose option reads differently in the game, as seen live on the worn Max
	 * cape (B16): its Feldip Hills teleport is "Carnivorous chinchompas" under Skilling Areas, Tele to POH is "Home".
	 */
	private static final Map<String, String> GAME_NAMES = Map.of(
		"feldip hills", "carnivorous chinchompas",
		"tele to poh", "home");
	private static final Pattern APOSTROPHE = Pattern.compile("['’]");
	private static final Pattern HOTKEY_LABEL = Pattern.compile("^([0-9A-Z])[.:] ");
	private static final Pattern TAGS = Pattern.compile("<[^>]*>");
	private static final Pattern KEY_BRACKET = Pattern.compile("^\\[[^]]*]\\s*");
	private static final Pattern KEY_PREFIX = Pattern.compile("^[0-9A-Z]\\s*[.:]\\s+");
	private static final Pattern ALIAS = Pattern.compile("\\s*\\([^)]*\\)\\s*$");
	private static final Pattern NOT_WORD = Pattern.compile("[^a-z0-9]+");
	private static final int SHORTEST_PARTIAL = 4;

	private MenuLabels()
	{
	}

	public static String normalise(String text)
	{
		if (text == null)
		{
			return "";
		}
		String plain = TAGS.matcher(text).replaceAll("").trim();
		plain = KEY_BRACKET.matcher(plain).replaceFirst("");
		plain = KEY_PREFIX.matcher(plain).replaceFirst("");
		plain = ALIAS.matcher(plain).replaceFirst("");
		plain = APOSTROPHE.matcher(plain).replaceAll("");
		return NOT_WORD.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
	}

	/**
	 * The same place: equal once normalised, or one starts the other ("Black chinchompa" and "Black chinchompas"). Never
	 * a match inside a name: "Ardougne" is not "West Ardougne".
	 */
	public static boolean matches(String line, String label)
	{
		return same(line, label) || line != null && line.lastIndexOf(':') > 0 && same(line.substring(line.lastIndexOf(':') + 1), label);
	}

	/** Diary Masters lines name the region first: "7: Karamja: Kaleb Paramaya (retired)" is Kaleb Paramaya. */
	private static boolean same(String line, String label)
	{
		String a = normalise(line);
		String b = normalise(label);
		if (a.isEmpty() || b.isEmpty())
		{
			return false;
		}
		String gameName = GAME_NAMES.get(b);
		if (gameName != null && a.equals(gameName))
		{
			return true;
		}
		if (a.equals(b))
		{
			return true;
		}
		return Math.min(a.length(), b.length()) >= SHORTEST_PARTIAL && (a.startsWith(b) || b.startsWith(a));
	}

	/**
	 * The position a catalog label's hotkey gives ("C. Le-sabrè" is the 12th line), or -1. The diary cape's sub-menu
	 * names regions and its list names "Morytania: Le-sabrè", but both keep the hotkey order (B16).
	 */
	static int hotkeyIndex(String label)
	{
		Matcher key = label == null ? null : HOTKEY_LABEL.matcher(label.trim());
		return key != null && key.find() ? MenuLines.HOTKEYS.indexOf(key.group(1).charAt(0)) : -1;
	}

	static boolean isOpener(String option)
	{
		return OPENERS.contains(normalise(option));
	}

	/** A fairy ring's "Last-destination (BKS)" option, when it is the code wanted. */
	static boolean isLastDestination(String option, String code)
	{
		String plain = TAGS.matcher(option == null ? "" : option).replaceAll("").toUpperCase(Locale.ROOT).replace(" ", "");
		return plain.contains("LAST-DESTINATION") && plain.contains("(" + code.replace("-", "") + ")");
	}
}
