package com.cluehelper.memory;

import com.cluehelper.Tables;
import java.time.Instant;
import java.util.*;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;
import static java.util.stream.Collectors.*;

/**
 * Client thread. Grown patches a route or a Sherlock task needs, named as the catalog and the step data name them: the
 * planted spirit trees and the Farming Guild redwood, and the allotments a planting task can't use while a crop grows.
 * The Time Tracking plugin remembers each patch for this account as core reads it, "region.varbit" to "value:seconds"
 * in its group. The spirit tree menu greys a planted tree that can't be used, in the label the game passes its list
 * script, so a tree counts as grown by whichever of the two saw it last.
 */
@Slf4j
@Singleton
public class GrownPatches
{
	static final String SPIRIT_TREES_KEY = "spiritTrees";
	private static final String FARMING_GUILD_REDWOOD = "Farming Guild redwood";
	private static final String TIME_TRACKING = "timetracking";
	private static final String REDWOOD_KEY = "4922." + VarbitID.FARMING_TRANSMIT_I;
	private static final int HARVESTABLE = 18;
	private static final int CHECK_HEALTH = 37;
	private static final int REGROWN_FIRST = 41;
	private static final int REGROWN_LAST = 55;
	private static final String MENU_TITLE = "Spirit Tree Locations";
	/** As core's farming tracker reads a spirit tree patch: 8-19 is growing, 21 and up diseased or dead. */
	private static final int GROWN_TREE = 20;
	/** The tag the game wraps a planted tree that can't be used in, in the label it passes the list script. */
	private static final String GREYED = "<col=5f5f5f>";
	private static final Seen UNTRACKED = new Seen();
	/** Spirit tree patch name → its Time Tracking key. */
	private static final Map<String, String> SPIRIT_TREE_PATCHES = Tables.byKey("spirit tree patches", row -> row.ids()[0] + "." + row.ids()[1]);
	/** A planting task's place to its allotments' varbits. */
	private static final Map<WorldPoint, int[]> ALLOTMENTS = Tables.rows("allotment patches").stream().collect(toUnmodifiableMap(
		row -> new WorldPoint(row.id(0), row.id(1), row.id(2)), row -> Arrays.copyOfRange(row.ids(), 3, row.getCells().size())));
	private static final int ALLOTMENT_FORMS = Tables.ids("allotment form")[0];
	/** How long an allotment crop takes to grow one stage. */
	private static final long ALLOTMENT_STAGE_SECONDS = 10 * 60;

	@Inject
	ConfigManager configManager;
	@Inject
	Client client;
	MemorySlot<Patches> menuSeen;

	@Inject
	void init(MemoryStore memory)
	{
		menuSeen = memory.slot(SPIRIT_TREES_KEY, Patches.class, Patches::new);
	}

	public Set<String> grown()
	{
		Set<String> grown = SPIRIT_TREE_PATCHES.keySet().stream().filter(this::isGrownTree).collect(toCollection(HashSet::new));
		if (isGrownRedwood(tracked(REDWOOD_KEY).value))
		{
			grown.add(FARMING_GUILD_REDWOOD);
		}
		return grown;
	}

	public boolean isOurs(ConfigChanged event)
	{
		return TIME_TRACKING.equals(event.getGroup()) && (REDWOOD_KEY.equals(event.getKey()) || SPIRIT_TREE_PATCHES.containsValue(event.getKey())
			|| ALLOTMENTS.entrySet().stream().anyMatch(place -> Arrays.stream(place.getValue()).anyMatch(varbit -> allotmentKey(place.getKey(), varbit).equals(event.getKey()))));
	}

	/** Every varbit of the planting places' allotments: a change to one can free a place. */
	public static Set<Integer> allotmentVarbits()
	{
		return ALLOTMENTS.values().stream().flatMapToInt(Arrays::stream).boxed().collect(toUnmodifiableSet());
	}

	/**
	 * The planting places whose every allotment has a crop still growing, read live where the place is loaded and else as
	 * Time Tracking last saw it, while the crop can't have grown since: none of them can be cleared or planted.
	 */
	public Set<WorldPoint> growingAllotments()
	{
		return ALLOTMENTS.entrySet().stream()
			.filter(place -> Arrays.stream(place.getValue()).allMatch(varbit -> isGrowing(place.getKey(), varbit)))
			.map(Map.Entry::getKey).collect(toUnmodifiableSet());
	}

	private boolean isGrowing(WorldPoint place, int varbit)
	{
		WorldView view = client.getTopLevelWorldView();
		boolean loaded = view != null && Arrays.stream(view.getMapRegions()).anyMatch(region -> region == place.getRegionID());
		Seen seen = loaded ? null : tracked(allotmentKey(place, varbit));
		int value = loaded ? client.getVarbitValue(varbit) : seen.value;
		int[] forms = client.getObjectDefinition(ALLOTMENT_FORMS).getImpostorIds();
		if (!isGrowingStage(forms, value))
		{
			return false;
		}
		return loaded || seen.seconds + stagesLeft(forms, value) * ALLOTMENT_STAGE_SECONDS > Instant.now().getEpochSecond();
	}

	private boolean isGrowingStage(int[] forms, int value)
	{
		return forms != null && value >= 0 && value < forms.length && forms[value] >= 0
			&& Allotment.of(value, client.getObjectDefinition(forms[value])) == null;
	}

	/** The growing stages after this one: a crop's stages are forms with ids in a row, ended by the grown one. */
	private int stagesLeft(int[] forms, int value)
	{
		int last = value;
		while (last + 1 < forms.length && forms[last + 1] == forms[last] + 1 && isGrowingStage(forms, last + 1))
		{
			last++;
		}
		return last - value;
	}

	private static String allotmentKey(WorldPoint place, int varbit)
	{
		return place.getRegionID() + "." + varbit;
	}

	/** Patches the menu doesn't list keep what was known: the menu only speaks for its own lines. */
	public void onListMenu(String title, List<String> rawLines)
	{
		if (!MENU_TITLE.equalsIgnoreCase(Text.removeTags(title == null ? "" : title).trim()))
		{
			return;
		}
		log.debug("spirit tree menu {}", rawLines);
		Patches next = new Patches();
		next.patches.putAll(menuSeen.get().patches);
		for (String raw : rawLines)
		{
			String name = placeOf(raw);
			if (SPIRIT_TREE_PATCHES.containsKey(name))
			{
				Seen seen = new Seen();
				seen.value = raw.toLowerCase().contains(GREYED) ? 0 : GROWN_TREE;
				seen.seconds = Instant.now().getEpochSecond();
				next.patches.put(name, seen);
			}
		}
		menuSeen.set(next);
	}

	/** A list line's place, without its coloured hotkey. */
	private static String placeOf(String raw)
	{
		String text = Text.removeTags(raw).trim();
		int colon = text.lastIndexOf(": ");
		return colon < 0 ? text : text.substring(colon + 2).trim();
	}

	private boolean isGrownTree(String patch)
	{
		Seen tracked = tracked(SPIRIT_TREE_PATCHES.get(patch));
		Seen menu = menuSeen.get().patches.get(patch);
		return (menu != null && menu.seconds >= tracked.seconds ? menu : tracked).value == GROWN_TREE;
	}

	/** Core's redwood patch values: chopping, or grown with its health check due first. */
	private static boolean isGrownRedwood(int value)
	{
		return value == HARVESTABLE || value == CHECK_HEALTH || value >= REGROWN_FIRST && value <= REGROWN_LAST;
	}

	/** {@link #UNTRACKED} when Time Tracking has never seen the patch. */
	private Seen tracked(String key)
	{
		String stored = configManager.getRSProfileConfiguration(TIME_TRACKING, key);
		String[] parts = stored == null ? new String[0] : stored.split(":");
		if (parts.length != 2 || !parts[0].matches("\\d+") || !parts[1].matches("\\d+"))
		{
			return UNTRACKED;
		}
		Seen seen = new Seen();
		seen.value = Integer.parseInt(parts[0]);
		seen.seconds = Long.parseLong(parts[1]);
		return seen;
	}

	static final class Patches
	{
		Map<String, Seen> patches = new HashMap<>();
	}

	static final class Seen
	{
		/** The patch varbit's value; from the menu, {@link #GROWN_TREE} or 0. */
		int value = -1;
		/** Epoch seconds when it was seen; 0 for a menu sighting stored before this was kept. */
		long seconds;
	}
}
