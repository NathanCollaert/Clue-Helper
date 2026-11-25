package com.cluehelper.guide;

import com.cluehelper.transport.*;
import java.util.*;
import java.util.function.Predicate;
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
 * names, and the guide's teleport lines name the place as it shows it.
 */
@Singleton
public class EasyTeleportsNames
{
	private static final String PLUGIN_CLASS = "EasyTeleportsPlugin";
	private static final String ENABLE_PREFIX = "enable";
	/** A section shared by several items names them apart: "Max/skill capes". */
	private static final String ITEMS_APART = "/";
	private static final String THE = "The ";
	private static final Comparator<Rename> LONGEST_SHOWN_FIRST = Comparator.comparingInt((Rename rename) -> rename.shown.length()).reversed();

	/** One place an enabled item renames: the section it is under, the game's name and the name shown instead. */
	@Value
	static class Rename
	{
		String items;
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

	/** The teleport line with its place as Easy Teleports shows it: "Amulet of glory: Edge". */
	public SubStep named(SubStep step)
	{
		CatalogRow row = step == null ? null : step.getRow();
		String pick = row == null ? null : row.getPick();
		String shown = pick == null || pick.isEmpty() ? null : shownPlace(row);
		String gameName = pick == null ? null : MenuLines.pickColoured(pick);
		if (shown == null || !step.getText().contains(gameName))
		{
			return step;
		}
		return step.toBuilder().text(step.getText().replace(gameName, MenuLines.pickColoured(shown))).build();
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

	/**
	 * The name shown for the row's place, under its own item's section first, else under any (the jewellery box shows the
	 * jewellery's); null while it shows the game's. A fairy ring's place is named by its code.
	 */
	private String shownPlace(CatalogRow teleport)
	{
		String place = teleport.getCategory() == Category.FAIRY_RING ? teleport.getCode() : teleport.getPick();
		if (place == null)
		{
			return null;
		}
		Predicate<Rename> forPlace = rename -> MenuLabels.matches(rename.gameName, place) || MenuLabels.matches(rename.gameName, withoutThe(place));
		List<Rename> all = renames();
		return all.stream().filter(forPlace).filter(rename -> namesItem(rename.items, teleport.getLabel())).findFirst()
			.or(() -> all.stream().filter(forPlace).findFirst())
			.map(Rename::getShown).orElse(null);
	}

	private static boolean namesItem(String items, String label)
	{
		String item = MenuLabels.normalise(label);
		return Arrays.stream(items.split(ITEMS_APART)).map(MenuLabels::normalise).anyMatch(name -> !name.isEmpty() && item.startsWith(name));
	}

	/** Easy Teleports names the Pendant of Ates' "The Darkfrost" without its article. */
	private static String withoutThe(String place)
	{
		return place.startsWith(THE) ? place.substring(THE.length()) : place;
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
				read.add(new Rename(items, item.name().trim(), shown));
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
