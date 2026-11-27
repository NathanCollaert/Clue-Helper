package com.cluehelper.guide;

import java.util.*;
import javax.inject.*;
import lombok.Value;
import net.runelite.client.config.*;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.*;
import net.runelite.client.util.Text;
import static java.util.stream.Collectors.*;

/**
 * The Easy Teleports plugin (easy-pharaoh-sceptre on the Plugin Hub) shows teleport options and dialog lines under
 * names the player picks. Its settings are read live while it runs: each item has an on/off setting and a section of the
 * same name holding a rename per place, labelled with the game's name for that place. Menu lines are matched under both
 * names.
 */
@Singleton
public class EasyTeleportsNames
{
	private static final String PLUGIN_CLASS = "EasyTeleportsPlugin";
	private static final String ENABLE_PREFIX = "enable";
	private static final Comparator<Rename> LONGEST_SHOWN_FIRST = Comparator.comparingInt((Rename rename) -> rename.shown.length()).reversed();

	/** One place an enabled item renames: the game's name and the name shown instead. */
	@Value
	static class Rename
	{
		String gameName;
		String shown;
	}

	@Inject
	ConfigManager configManager;
	@Inject
	PluginManager pluginManager;
	/** Null until read again after its settings change or a plugin starts or stops. */
	volatile List<Rename> renames;
	/** Its settings' group, from the last read. */
	volatile String group;

	/** The text as shown, then the same text with the game's name for each name Easy Teleports put in it. */
	public List<String> readings(String shown)
	{
		return readings(shown, renames());
	}

	/** Whether the text shows a name from its settings, while it runs with that item turned on. */
	public boolean renamed(String shown)
	{
		String plain = shown == null ? "" : Text.removeTags(shown);
		return renames().stream().anyMatch(rename -> plain.contains(rename.shown));
	}

	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals(group))
		{
			renames = null;
		}
	}

	/** Installing, uninstalling, turning on or off: any plugin's start or stop. */
	public void onPluginChanged()
	{
		renames = null;
	}

	private List<Rename> renames()
	{
		List<Rename> read = renames;
		if (read == null)
		{
			read = readRenames();
			renames = read;
		}
		return read;
	}

	/** None while it doesn't run: its settings outlive it once turned off or uninstalled. */
	private List<Rename> readRenames()
	{
		Plugin plugin = pluginManager.getPlugins().stream()
			.filter(running -> running.getClass().getSimpleName().equals(PLUGIN_CLASS) && pluginManager.isPluginActive(running))
			.findFirst().orElse(null);
		if (plugin == null)
		{
			return List.of();
		}
		ConfigDescriptor settings = configManager.getConfigDescriptor(pluginManager.getPluginConfigProxy(plugin));
		group = settings.getGroup().value();
		Map<String, String> sectionNames = settings.getSections().stream()
			.collect(toMap(ConfigSectionDescriptor::key, section -> section.name().trim()));
		Set<String> enabledItems = settings.getItems().stream()
			.filter(item -> item.key().startsWith(ENABLE_PREFIX) && Boolean.parseBoolean(configManager.getConfiguration(group, item.key())))
			.map(item -> item.name().trim()).collect(toSet());
		List<Rename> read = new ArrayList<>();
		for (ConfigItemDescriptor item : settings.getItems())
		{
			String items = sectionNames.getOrDefault(item.getItem().section(), "");
			String value = item.getType() == String.class ? configManager.getConfiguration(group, item.key()) : null;
			String shown = value == null ? "" : Text.removeTags(value).trim();
			if (enabledItems.contains(items) && !shown.isEmpty())
			{
				read.add(new Rename(item.name().trim(), shown));
			}
		}
		read.sort(LONGEST_SHOWN_FIRST);
		return List.copyOf(read);
	}

	/** Easy Teleports writes its name over the game's, either as the whole text or in place inside it; longest first. */
	static List<String> readings(String shown, List<Rename> renames)
	{
		if (shown == null || renames.isEmpty())
		{
			return Collections.singletonList(shown);
		}
		String plain = Text.removeTags(shown);
		Rename found = renames.stream().filter(rename -> plain.contains(rename.shown)).findFirst().orElse(null);
		if (found == null)
		{
			return Collections.singletonList(shown);
		}
		int at = plain.indexOf(found.shown);
		List<String> readings = new ArrayList<>();
		readings.add(shown);
		renames.stream().filter(rename -> rename.shown.equals(found.shown))
			.forEach(rename -> readings.add(plain.substring(0, at) + rename.gameName + plain.substring(at + found.shown.length())));
		return readings;
	}
}
