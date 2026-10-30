package com.cluehelper.guide;

import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ObjectID;

/**
 * The Abyss's outer ring: one obstacle a spot, its form (passage, gap, eyes, rock, tendrils, boil) set per visit, while
 * Shortest Path names one spot for every way through. The ring is crossed through the obstacle nearest the player that
 * they can pass; the guide's outline and the path line both take it from here, so they agree. Client thread only.
 */
@Singleton
public class AbyssObstacles
{
	private static final Set<Integer> IDS = Set.of(ObjectID.RCU_OUTER_MULTI1, ObjectID.RCU_OUTER_MULTI2,
		ObjectID.RCU_OUTER_MULTI3, ObjectID.RCU_OUTER_MULTI4, ObjectID.RCU_OUTER_MULTI5, ObjectID.RCU_OUTER_MULTI6,
		ObjectID.RCU_OUTER_MULTI7, ObjectID.RCU_OUTER_MULTI8, ObjectID.RCU_OUTER_MULTI9, ObjectID.RCU_OUTER_MULTI10,
		ObjectID.RCU_OUTER_MULTI11);
	/** The forms crossed without a pickaxe, an axe or a tinderbox. */
	private static final Set<String> WITHOUT_TOOLS = Set.of("Passage", "Gap", "Eyes");
	/** The tool each other form takes, as the last word of the tool's name: a rune pickaxe mines, an infernal axe chops. */
	private static final Map<String, String> TOOLS = Map.of("Rock", "pickaxe", "Tendrils", "axe", "Boil", "tinderbox");

	@Inject
	private Client client;
	@Inject
	private SceneObjects sceneObjects;
	/** Kept while it stays in sight and passable, so the pick doesn't flip to another as the player runs past. */
	private TileObject chosen;

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
		chosen = obstacles.stream().filter(obstacle -> isObstacle(obstacle.getId()) && canPass(obstacle, tools))
			.min(Comparator.comparingInt(obstacle -> obstacle.getWorldLocation().distanceTo2D(from))).orElse(null);
		return chosen;
	}

	private boolean canPass(TileObject obstacle, Set<String> tools)
	{
		String form = LeftClicks.shownForm(client.getObjectDefinition(obstacle.getId())).getName();
		return WITHOUT_TOOLS.contains(form) || tools.contains(TOOLS.get(form));
	}

	/** The last word of every item carried or worn: "pickaxe" for a rune pickaxe. */
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
					String[] name = client.getItemDefinition(item.getId()).getName().toLowerCase(Locale.ROOT).split(" ");
					words.add(name[name.length - 1]);
				}
			}
		}
		return words;
	}
}
