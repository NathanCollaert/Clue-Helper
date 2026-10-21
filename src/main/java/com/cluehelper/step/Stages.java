package com.cluehelper.step;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.CrypticClue;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/** Builds the stages of steps done in several places. Act texts never repeat core's solutions. */
public final class Stages
{
	/** Key NPCs wander over a camp, a floor or a field. */
	static final int KEY_AREA_TILES = 15;
	private static final String CORE_DETAILS = "The Clue Scroll plugin shows the details";

	private Stages()
	{
	}

	/** Every stage's tiles in order: the step's targets. */
	public static List<WorldPoint> tiles(List<Stage> stages)
	{
		List<WorldPoint> tiles = new ArrayList<>();
		stages.forEach(stage -> tiles.addAll(stage.getTiles()));
		return List.copyOf(tiles);
	}

	static List<Stage> parts(List<CrypticClue> parts, ClueScrollPlugin plugin)
	{
		List<Stage> stages = new ArrayList<>();
		for (CrypticClue part : parts)
		{
			WorldPoint at = part.getLocation(plugin);
			if (at != null)
			{
				stages.add(Stage.builder().kind(Stage.Kind.PART).act(partAct(part, plugin)).detail(CORE_DETAILS).tiles(List.of(at)).build());
			}
		}
		return List.copyOf(stages);
	}

	private static String partAct(CrypticClue part, ClueScrollPlugin plugin)
	{
		if (part.isRequiresSpade())
		{
			return "Dig";
		}
		String npc = part.getNpc(plugin);
		if (npc != null)
		{
			return "Talk to " + npc;
		}
		return "Search it";
	}

	/** Kill one of the key's NPCs where the cache says, then search the step's own object. */
	static List<Stage> killForKey(StepIndex.Target key, String npc, ItemRequirement keyItem, List<WorldPoint> targets)
	{
		Stage kill = Stage.builder().kind(Stage.Kind.KEY).act("Get the key from " + (npc == null ? "the NPC" : npc))
			.detail(key.getText()).tiles(List.of(key.getKeyTile())).ourMarker(true).radius(KEY_AREA_TILES).item(keyItem).build();
		return List.of(kill, Stage.builder().kind(Stage.Kind.FINAL).tiles(targets).build());
	}

	/**
	 * Sherlock: the task (at its places, or anywhere when the data has none) until core marks it completed, then
	 * Sherlock. Charlie: the item's sources while the data has any, then Charlie. Empty when only the hand-in is left.
	 */
	static List<Stage> challenge(StepIndex.Entry entry, boolean completed, ItemRequirement returnItem, List<WorldPoint> targets,
		Function<StepIndex.Requires, ItemRequirement> tools)
	{
		List<Stage> stages = new ArrayList<>();
		if (returnItem == null && !completed)
		{
			List<WorldPoint> places = entry == null ? List.of() : placeTiles(entry.getPlaces());
			String where = entry == null || !entry.hasPlaces() ? CORE_DETAILS
				: entry.getPlaces().isEmpty() ? "Anywhere, with what it needs" : entry.getPlaces().get(0).getText();
			stages.add(Stage.builder().kind(Stage.Kind.TASK).act("Do Sherlock's task").detail(where).tiles(places)
				.ourMarker(true).build());
		}
		if (returnItem != null && entry != null && !entry.getSources().isEmpty())
		{
			StepIndex.Place source = entry.getSources().get(0);
			List<ItemRequirement> needed = source.getTools().stream().map(tools).collect(Collectors.toList());
			stages.add(Stage.builder().kind(Stage.Kind.FETCH).act("Get the item").detail(source.getText())
				.tiles(placeTiles(entry.getSources())).ourMarker(true).item(returnItem).tools(List.copyOf(needed)).build());
		}
		if (stages.isEmpty())
		{
			return List.of();
		}
		stages.add(Stage.builder().kind(Stage.Kind.FINAL).tiles(targets).build());
		return List.copyOf(stages);
	}

	private static List<WorldPoint> placeTiles(List<StepIndex.Place> places)
	{
		List<WorldPoint> tiles = new ArrayList<>();
		places.forEach(place -> tiles.add(place.getTile()));
		return List.copyOf(tiles);
	}
}
