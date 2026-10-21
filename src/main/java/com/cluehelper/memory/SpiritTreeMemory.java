package com.cluehelper.memory;

import com.cluehelper.player.PlayerModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.HashTable;
import net.runelite.api.Player;
import net.runelite.api.WidgetNode;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.client.util.Text;

/**
 * Which planted spirit trees are grown (PLAN §5.2, B17), as Shortest Path learns them: the spirit tree menu lists the
 * planted patches, greyed when the tree can't be used; inside a patch's region its {@code FARMING_TRANSMIT_*} varbit
 * is 20 for a grown tree. Remembered per profile with the date. A patch never observed is not grown. Client thread.
 */
@Singleton
public class SpiritTreeMemory
{
	static final String KEY = "spiritTrees";
	public static final String MENU_TITLE = "Spirit Tree Locations";
	/** The patch varbit's value for a grown, usable tree (core's farming tracker: 8-19 growing, 21+ diseased or dead). */
	static final int GROWN = 20;
	private static final String GREYED = "5f5f5f";

	/** Patch name, its region, and the region-scoped transmit varbit that describes it there. */
	private static final Map<String, int[]> PATCHES = Map.of(
		"Port Sarim", new int[]{12082, VarbitID.FARMING_TRANSMIT_A},
		"Etceteria", new int[]{10300, VarbitID.FARMING_TRANSMIT_B},
		"Brimhaven", new int[]{11058, VarbitID.FARMING_TRANSMIT_B},
		"Hosidius", new int[]{6711, VarbitID.FARMING_TRANSMIT_F},
		"Farming Guild", new int[]{4922, VarbitID.FARMING_TRANSMIT_A});

	private final Client client;
	private final PlayerModel player;
	private final MemorySlot<Patches> remembered;
	private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
	private Set<String> published;
	private int lastRegion = -1;
	private int lastRegionTick = -1;
	private int settledRegion = -1;

	@Inject
	SpiritTreeMemory(Client client, MemoryStore memory, PlayerModel player)
	{
		this.client = client;
		this.player = player;
		this.remembered = memory.slot(KEY, Patches.class, Patches::new);
	}

	/** Planted trees known grown, by patch name. */
	public Set<String> grown()
	{
		return remembered.get().patches.entrySet().stream().filter(e -> e.getValue().value == GROWN)
			.map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
	}

	/** Log lines for the dev trace. */
	public Runnable addListener(Consumer<String> listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** On start and after a profile change: what this profile remembers goes to the snapshot. */
	public void publish()
	{
		Set<String> now = grown();
		if (!now.equals(published))
		{
			published = now;
			player.setPlantedTrees(now);
			tell("grown " + now);
		}
	}

	/**
	 * A spirit tree menu just opened: each planted patch it lists is grown unless its line is greyed. Patches it doesn't
	 * list keep what was known (Shortest Path: the menu is authoritative only for its own lines).
	 */
	public void onListMenu(String title, List<String> rawLines)
	{
		if (!MENU_TITLE.equalsIgnoreCase(Text.removeTags(title == null ? "" : title).trim()))
		{
			return;
		}
		tell("menu " + rawLines);
		for (String raw : rawLines)
		{
			String name = placeOf(raw);
			if (PATCHES.containsKey(name))
			{
				observe(name, raw.toLowerCase().contains(GREYED) ? 0 : GROWN, "menu");
			}
		}
		publish();
	}

	/**
	 * In a patch's region its transmit varbit describes that patch, once the region has held for two ticks (the slot is
	 * refilled after a region change) and while no modal interface is open (varbits aren't sent then).
	 */
	public void onGameTick()
	{
		Player local = client.getLocalPlayer();
		WorldPoint at = local == null ? null : WorldPoint.fromLocalInstance(client, local.getLocalLocation());
		int region = at == null ? -1 : at.getRegionID();
		int tick = client.getTickCount();
		settledRegion = region != -1 && region == lastRegion && tick == lastRegionTick + 1 ? region : -1;
		lastRegion = region;
		lastRegionTick = tick;
		if (settledRegion == -1 || modalOpen())
		{
			return;
		}
		for (Map.Entry<String, int[]> patch : PATCHES.entrySet())
		{
			if (patch.getValue()[0] == settledRegion)
			{
				observe(patch.getKey(), client.getVarbitValue(patch.getValue()[1]), "region");
				publish();
			}
		}
	}

	/** "<col=735a28>7</col>: Port Sarim" → "Port Sarim". */
	static String placeOf(String raw)
	{
		String text = Text.removeTags(raw).trim();
		int colon = text.lastIndexOf(": ");
		return colon < 0 ? text : text.substring(colon + 2).trim();
	}

	private void observe(String patch, int value, String source)
	{
		Seen old = remembered.get().patches.get(patch);
		if (old != null && old.value == value)
		{
			return;
		}
		Patches next = new Patches();
		next.patches.putAll(remembered.get().patches);
		Seen seen = new Seen();
		seen.value = value;
		seen.seenAt = System.currentTimeMillis();
		next.patches.put(patch, seen);
		remembered.set(next);
		tell(patch + " = " + value + " (" + source + ")");
	}

	private boolean modalOpen()
	{
		HashTable<WidgetNode> table = client.getComponentTable();
		if (table == null)
		{
			return false;
		}
		for (WidgetNode node : table)
		{
			if (node.getModalMode() != WidgetModalMode.NON_MODAL)
			{
				return true;
			}
		}
		return false;
	}

	private void tell(String line)
	{
		listeners.forEach(listener -> listener.accept(line));
	}

	static final class Patches
	{
		Map<String, Seen> patches = new HashMap<>();
	}

	static final class Seen
	{
		/** The patch varbit's value, or 20 / 0 from the menu. */
		int value;
		long seenAt;
	}
}
