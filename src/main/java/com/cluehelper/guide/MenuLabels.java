package com.cluehelper.guide;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text matching is only for lines no script argument identifies: the nexus and the jewellery box are matched by struct
 * id and index instead, because better-teleport-menu rewrites their text.
 */
public final class MenuLabels
{
	static final Set<String> TELEPORT_LIST_OPENERS = Set.of("rub", "open", "teleport", "teleports", "teleport menu", "operate",
		"travel", "tree", "activate", "configure", "ring configure", "perks", "spellbook", "commune");
	/** Catalog (Shortest Path) labels that the worn Max cape's options name differently. */
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
	private static final String TELEPORT_SUFFIX = " teleport";

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

	public static boolean matches(String line, String label)
	{
		return same(line, label) || same(withoutRegionPrefix(line), label);
	}

	/** Diary Masters lines name the region first: "7: Karamja: Kaleb Paramaya (retired)". */
	private static String withoutRegionPrefix(String line)
	{
		int lastColon = line == null ? -1 : line.lastIndexOf(':');
		return lastColon > 0 ? line.substring(lastColon + 1) : null;
	}

	/** A prefix matches plurals ("Black chinchompas"), never a name inside another: "Ardougne" isn't "West Ardougne". */
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
		if (a.equals(b) || namesPlaceOf(a, b))
		{
			return true;
		}
		return Math.min(a.length(), b.length()) >= SHORTEST_PARTIAL && (a.startsWith(b) || b.startsWith(a));
	}

	/** Item options like the Ardougne cloak's "Monastery Teleport" for the catalog's "Kandarin Monastery". */
	private static boolean namesPlaceOf(String option, String label)
	{
		if (!option.endsWith(TELEPORT_SUFFIX) || option.length() == TELEPORT_SUFFIX.length())
		{
			return false;
		}
		List<String> words = List.of(label.split(" "));
		return words.containsAll(List.of(option.substring(0, option.length() - TELEPORT_SUFFIX.length()).split(" ")));
	}

	/**
	 * The diary cape's sub-menu and list name a line differently ("Morytania: Le-sabrè") but keep the hotkey order, so a
	 * label's hotkey ("C. Le-sabrè") gives its line, or -1.
	 */
	static int hotkeyIndex(String label)
	{
		Matcher key = label == null ? null : HOTKEY_LABEL.matcher(label.trim());
		return key != null && key.find() ? MenuLines.HOTKEYS.indexOf(key.group(1).charAt(0)) : -1;
	}

	/** Whether the line as displayed already starts with its key: "C. Le-sabrè", "1: Tree Gnome Village", "[G] Varrock". */
	static boolean showsKey(String text, char key)
	{
		String plain = TAGS.matcher(text == null ? "" : text).replaceAll("").trim();
		String upper = plain.toUpperCase(Locale.ROOT);
		return upper.startsWith("[" + key + "]") || upper.startsWith(key + ".") || upper.startsWith(key + ":");
	}

	static boolean isOpener(String option)
	{
		return TELEPORT_LIST_OPENERS.contains(normalise(option));
	}

	static boolean isLastDestination(String option, String code)
	{
		String plain = TAGS.matcher(option == null ? "" : option).replaceAll("").toUpperCase(Locale.ROOT).replace(" ", "");
		return plain.contains("LAST-DESTINATION") && plain.contains("(" + code.replace("-", "") + ")");
	}
}
