package com.cluehelper.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

	static List<Stage> parts(List<CrypticClue> parts, ClueScrollPlugin plugin, Function<CrypticClue, List<String>> wornFor)
	{
		List<Stage> stages = new ArrayList<>();
		for (CrypticClue part : parts)
		{
			WorldPoint at = part.getLocation(plugin);
			if (at != null)
			{
				stages.add(Stage.builder().kind(Stage.Kind.PART).act(partAct(part, plugin)).tiles(List.of(at)).worn(wornFor.apply(part))
					.build());
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
			return "Talk to " + NpcAliases.either(npc);
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
	static List<Stage> challenge(StepIndex.Entry entry, String task, boolean completed, ItemRequirement returnItem,
		List<WorldPoint> targets, Function<StepIndex.Requires, ItemRequirement> tools)
	{
		List<Stage> stages = new ArrayList<>();
		if (returnItem == null && !completed)
		{
			List<WorldPoint> places = entry == null ? List.of() : placeTiles(entry.getPlaces());
			String where = entry == null || !entry.hasPlaces() ? null
				: entry.getPlaces().isEmpty() ? "Anywhere, with what it needs" : entry.getPlaces().get(0).getText();
			stages.add(Stage.builder().kind(Stage.Kind.TASK).act(sentence(task)).detail(where).tiles(places)
				.ourMarker(true).objectIds(entry == null ? Set.of() : objectsOf(entry.getPlaces())).takesStepItems(true)
				.grownOnly(entry == null ? Map.of() : grownOnly(entry.getPlaces())).build());
		}
		if (returnItem != null && entry != null && !entry.getSources().isEmpty())
		{
			StepIndex.Place source = entry.getSources().get(0);
			List<ItemRequirement> needed = source.getTools().stream().map(tools).collect(Collectors.toList());
			stages.add(Stage.builder().kind(Stage.Kind.FETCH).act("Get the item").detail(source.getText())
				.tiles(placeTiles(entry.getSources())).ourMarker(true).item(returnItem).tools(List.copyOf(needed))
				.objectIds(objectsOf(entry.getSources())).build());
		}
		if (stages.isEmpty())
		{
			return List.of();
		}
		stages.add(Stage.builder().kind(Stage.Kind.FINAL).tiles(targets).build());
		return List.copyOf(stages);
	}

	/** "burn a redwood log." as a step: "Burn a redwood log". */
	private static String sentence(String task)
	{
		if (task == null || task.isBlank())
		{
			return "Do Sherlock's task";
		}
		String trimmed = task.trim().replaceAll("[.!]+$", "");
		return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
	}

	private static Map<WorldPoint, String> grownOnly(List<StepIndex.Place> places)
	{
		return places.stream().filter(place -> place.getGrown() != null)
			.collect(Collectors.toUnmodifiableMap(StepIndex.Place::getTile, StepIndex.Place::getGrown));
	}

	private static Set<Integer> objectsOf(List<StepIndex.Place> places)
	{
		return places.stream().flatMap(place -> place.getObjects().stream()).collect(Collectors.toUnmodifiableSet());
	}

	private static List<WorldPoint> placeTiles(List<StepIndex.Place> places)
	{
		List<WorldPoint> tiles = new ArrayList<>();
		places.forEach(place -> tiles.add(place.getTile()));
		return List.copyOf(tiles);
	}
}
