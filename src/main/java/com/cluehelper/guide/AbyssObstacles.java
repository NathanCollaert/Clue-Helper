package com.cluehelper.guide;

import com.cluehelper.Tables;
import com.cluehelper.player.PlayerModel;
import java.util.*;
import java.util.stream.IntStream;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import static java.util.stream.Collectors.*;

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
	/** The tools each other form takes: every pickaxe for a rock, every axe and hatchet for tendrils. */
	private static final Map<String, Set<Integer>> TOOLS = Tables.byKey("abyss tools",
		row -> IntStream.of(row.ids()).boxed().collect(toUnmodifiableSet()));

	@Inject
	Client client;
	@Inject
	SceneObjects sceneObjects;
	@Inject
	PlayerModel player;
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
		Set<Integer> carried = player.carriedIds();
		if (chosen != null && obstacles.contains(chosen) && canPass(chosen, carried))
		{
			return chosen;
		}
		WorldPoint from = local.getWorldLocation();
		chosen = obstacles.stream().filter(obstacle -> canPass(obstacle, carried))
			.min(Comparator.comparingInt(obstacle -> obstacle.getWorldLocation().distanceTo2D(from))).orElse(null);
		return chosen;
	}

	private boolean canPass(TileObject obstacle, Set<Integer> carried)
	{
		String form = LeftClicks.shownForm(client.getObjectDefinition(obstacle.getId())).getName();
		return WITHOUT_TOOLS.contains(form) || !Collections.disjoint(TOOLS.getOrDefault(form, Set.of()), carried);
	}
}
