package com.cluehelper.guide;

import com.cluehelper.Tables;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;

/**
 * The Abyss's outer ring: one obstacle a spot, its form (passage, gap, eyes, rock, tendrils, boil) set per visit, while
 * Shortest Path names one spot for every way through. The ring is crossed through the obstacle nearest the player that
 * they can pass; the guide's outline and the path line both take it from here, so they agree. Client thread only.
 */
@Singleton
public class AbyssObstacles
{
	private static final Set<Integer> IDS = Tables.idSet("abyss obstacles");
	/** The forms crossed without a pickaxe, an axe or a tinderbox. */
	private static final Set<String> WITHOUT_TOOLS = Set.of("Passage", "Gap", "Eyes");
	/** The tools each other form takes, as the last word of the tool's name: a rune pickaxe mines, an Imcando hatchet chops. */
	private static final Map<String, Set<String>> TOOLS = Map.of("Rock", Set.of("pickaxe"), "Tendrils", Set.of("axe", "hatchet"),
		"Boil", Set.of("tinderbox"));
	/** A variant's suffix: "Dragon pickaxe (or)", "Crystal axe (inactive)". */
	private static final String VARIANT_SUFFIX = " \\(.*\\)$";

	@Inject
	Client client;
	@Inject
	SceneObjects sceneObjects;
	/** Kept while it stays in sight and passable, so the pick doesn't flip to another as the player runs past. */
	TileObject chosen;

	public static boolean isObstacle(int objectId)
	{
		return IDS.contains(objectId);
	}

	/** The obstacle in sight to cross by; null with none passable. */
	public TileObject choose()
	{
		List<TileObject> obstacles = sceneObjects.find(IDS);
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return null;
		}
		Set<String> tools = carriedToolWords();
		if (chosen != null && obstacles.contains(chosen) && canPass(chosen, tools))
		{
			return chosen;
		}
		WorldPoint from = local.getWorldLocation();
		chosen = obstacles.stream().filter(obstacle -> canPass(obstacle, tools))
			.min(Comparator.comparingInt(obstacle -> obstacle.getWorldLocation().distanceTo2D(from))).orElse(null);
		return chosen;
	}

	private boolean canPass(TileObject obstacle, Set<String> tools)
	{
		String form = LeftClicks.shownForm(client.getObjectDefinition(obstacle.getId())).getName();
		return WITHOUT_TOOLS.contains(form) || TOOLS.getOrDefault(form, Set.of()).stream().anyMatch(tools::contains);
	}

	/** The tool word of every item carried or worn. */
	private Set<String> carriedToolWords()
	{
		Set<String> words = new HashSet<>();
		for (int container : new int[]{InventoryID.INV, InventoryID.WORN})
		{
			ItemContainer items = client.getItemContainer(container);
			for (Item item : items == null ? new Item[0] : items.getItems())
			{
				if (item.getId() >= 0)
				{
					words.add(toolWord(client.getItemDefinition(item.getId()).getName()));
				}
			}
		}
		return words;
	}

	/** The last word of an item's name, past any variant suffix: "pickaxe" for a dragon pickaxe (or). */
	static String toolWord(String itemName)
	{
		String[] words = itemName.replaceFirst(VARIANT_SUFFIX, "").toLowerCase(Locale.ROOT).split(" ");
		return words[words.length - 1];
	}
}
