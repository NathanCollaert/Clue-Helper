package com.cluehelper.guide;

import com.cluehelper.Tables;
import java.util.*;
import java.util.function.Function;
import javax.inject.*;
import net.runelite.client.config.*;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;

/**
 * The Easy Teleports plugin (easy-pharaoh-sceptre on the Plugin Hub) shows teleport options and dialog lines under
 * names the player picks. Its settings give each of those names back the game's own, which the catalog's labels match.
 */
@Singleton
public class EasyTeleportsNames
{
	private static final String GROUP = "easypharaohsceptre";
	/** RuneLite keeps whether a plugin is on under its class name in lower case; unset means on. */
	private static final String PLUGIN_ON = "easyteleportsplugin";
	private static final List<Tables.Row> NAMES = Tables.rows("easy teleports names");
	private static final int RENAME_KEY = 0;
	private static final int ENABLE_KEY = 1;
	private static final int UNSET_RENAME = 2;
	private static final Comparator<String> LONGEST_FIRST =
		Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder());

	@Inject
	ConfigManager configManager;
	/** Each shown name to the game names it stands for, read again after any change to the settings. */
	volatile Map<String, List<String>> gameNames;

	/** The text as shown, then the same text with the game's name for each name Easy Teleports put in it. */
	public List<String> readings(String shown)
	{
		return readings(shown, gameNames());
	}

	public void onConfigChanged(ConfigChanged event)
	{
		if (GROUP.equals(event.getGroup()) || RuneLiteConfig.GROUP_NAME.equals(event.getGroup()))
		{
			gameNames = null;
		}
	}

	private Map<String, List<String>> gameNames()
	{
		Map<String, List<String>> names = gameNames;
		if (names == null)
		{
			names = isPluginOn() ? gameNames(key -> configManager.getConfiguration(GROUP, key)) : Map.of();
			gameNames = names;
		}
		return names;
	}

	private boolean isPluginOn()
	{
		return !Boolean.FALSE.toString().equals(configManager.getConfiguration(RuneLiteConfig.GROUP_NAME, PLUGIN_ON));
	}

	/** Longest name first, so a name that holds a shorter one is read whole. */
	static Map<String, List<String>> gameNames(Function<String, String> setting)
	{
		Map<String, List<String>> byShown = new TreeMap<>(LONGEST_FIRST);
		for (Tables.Row row : NAMES)
		{
			List<String> cells = row.getCells();
			if (!Boolean.parseBoolean(setting.apply(cells.get(ENABLE_KEY))))
			{
				continue;
			}
			String chosen = setting.apply(cells.get(RENAME_KEY));
			String shown = Text.removeTags(chosen == null ? cells.get(UNSET_RENAME) : chosen).trim();
			if (!shown.isEmpty())
			{
				byShown.computeIfAbsent(shown, name -> new ArrayList<>()).add(row.getKey());
			}
		}
		return Collections.unmodifiableMap(byShown);
	}

	/** Easy Teleports writes its name over the game's, either as the whole text or in place inside it. */
	static List<String> readings(String shown, Map<String, List<String>> gameNames)
	{
		if (shown == null || gameNames.isEmpty())
		{
			return Collections.singletonList(shown);
		}
		String plain = Text.removeTags(shown);
		for (Map.Entry<String, List<String>> name : gameNames.entrySet())
		{
			int at = plain.indexOf(name.getKey());
			if (at < 0)
			{
				continue;
			}
			List<String> readings = new ArrayList<>();
			readings.add(shown);
			for (String gameName : name.getValue())
			{
				readings.add(plain.substring(0, at) + gameName + plain.substring(at + name.getKey().length()));
			}
			return readings;
		}
		return Collections.singletonList(shown);
	}
}
