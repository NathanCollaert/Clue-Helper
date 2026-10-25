package com.cluehelper.step;

import com.cluehelper.player.Book;
import com.cluehelper.player.PositionRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.runelite.api.Prayer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.CrypticClue;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/** Builds the stages of steps done in several places. Act texts never repeat core's solutions. */
public final class Stages
{
	/** Key NPCs wander over a camp, a floor or a field. */
	private static final int KEY_AREA_TILES = 15;
	private static final String ANYWHERE = "Anywhere, with what it needs";
	private static final Pattern ACTIVATE_PRAYER = Pattern.compile("activate the (\\w+) prayer\\.?");

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

	/** The task's places drop once the player owns one of the entry's {@code anywhereWith} items (a sacred eel). */
	/** The step as the player's state leaves it: a task done anywhere once an item is owned, or under way inside its area. */
	public static Step adjusted(Step step, Predicate<Integer> owns, WorldPoint at)
	{
		return underWayInSpan(doneAnywhereWhenOwned(step, owns), at);
	}

	private static Step doneAnywhereWhenOwned(Step step, Predicate<Integer> owns)
	{
		StepIndex.Entry entry = step.getData();
		if (entry == null || entry.getAnywhereWith().stream().noneMatch(owns))
		{
			return step;
		}
		Set<WorldPoint> places = step.getStages().stream().filter(stage -> stage.getKind() == Stage.Kind.TASK)
			.flatMap(stage -> stage.getTiles().stream()).collect(Collectors.toSet());
		List<Stage> stages = step.getStages().stream().map(stage -> stage.getKind() != Stage.Kind.TASK ? stage
			: stage.toBuilder().tiles(List.of()).objectIds(Set.of()).npcIds(Set.of()).option(null).detail(ANYWHERE).build())
			.collect(Collectors.toUnmodifiableList());
		return step.toBuilder().stages(stages)
			.targets(step.getTargets().stream().filter(tile -> !places.contains(tile)).collect(Collectors.toUnmodifiableList())).build();
	}

	/** Inside a task's area (on a rooftop course) the task is under way: done where the player is, not from its start. */
	private static Step underWayInSpan(Step step, WorldPoint at)
	{
		Stage task = step.getStages().stream().filter(stage -> stage.getKind() == Stage.Kind.TASK && !stage.getTiles().isEmpty()
			&& stage.getSpans().stream().anyMatch(place -> place.spans(at))).findFirst().orElse(null);
		if (task == null)
		{
			return step;
		}
		List<Stage> stages = step.getStages().stream().map(stage -> stage != task ? stage : stage.toBuilder().tiles(List.of()).build())
			.collect(Collectors.toUnmodifiableList());
		return step.toBuilder().stages(stages)
			.targets(step.getTargets().stream().filter(tile -> !task.getTiles().contains(tile)).collect(Collectors.toUnmodifiableList()))
			.build();
	}

	/** One place per distinct area. */
	private static List<StepIndex.Place> spans(List<StepIndex.Place> places)
	{
		List<StepIndex.Place> spans = new ArrayList<>();
		for (StepIndex.Place place : places)
		{
			if (place.hasSpan() && spans.stream().noneMatch(kept -> Arrays.equals(kept.getSpan(), place.getSpan())))
			{
				spans.add(place);
			}
		}
		return List.copyOf(spans);
	}

	/**
	 * Sherlock: the task (at its places, or anywhere when the data has none) until core marks it completed, then
	 * Sherlock. Empty when only the hand-in is left, and for Charlie, whose item is a need of the step like Sherlock's
	 * items: the player brings it.
	 */
	static List<Stage> challenge(StepIndex.Entry entry, String task, boolean completed, ItemRequirement returnItem,
		List<WorldPoint> targets, List<ItemRequirement> emptied)
	{
		List<Stage> stages = new ArrayList<>();
		if (returnItem == null && !completed)
		{
			List<WorldPoint> places = entry == null ? List.of() : placeTiles(entry.getPlaces());
			String where = entry == null || !entry.hasPlaces() ? null
				: entry.getPlaces().isEmpty() ? ANYWHERE : entry.getPlaces().get(0).getText();
			stages.add(Stage.builder().kind(Stage.Kind.TASK).act(sentence(task)).detail(where).tiles(places)
				.ourMarker(true).objectIds(entry == null ? Set.of() : objectsOf(entry.getPlaces()))
				.npcIds(entry == null ? Set.of() : npcsOf(entry.getPlaces())).takesStepItems(true)
				.worn(entry == null ? List.of() : entry.getWear()).emptied(emptied).before(entry == null ? List.of() : entry.getBefore())
				.spans(entry == null ? List.of() : spans(entry.getPlaces()))
				.option(entry == null ? null : entry.getPlaces().stream().map(StepIndex.Place::getOption).filter(option -> option != null)
					.findFirst().orElse(null))
				.grownOnly(entry == null ? Map.of() : grownOnly(entry.getPlaces()))
				.houseObjects(entry == null ? Set.of() : entry.getPlaces().stream().filter(place -> PositionRules.POH_NODE.equals(place.getTile()))
					.flatMap(place -> place.getObjects().stream()).collect(Collectors.toUnmodifiableSet()))
				.uses(entry == null ? Map.of() : usesByTile(entry.getPlaces()))
				.book(entry == null || entry.getBook() == null ? null : Book.valueOf(entry.getBook())).build());
		}
		if (stages.isEmpty())
		{
			return List.of();
		}
		stages.add(Stage.builder().kind(Stage.Kind.FINAL).tiles(targets).build());
		return List.copyOf(stages);
	}

	/** The prayer a Sherlock task turns on ("activate the chivalry prayer."), or null. */
	public static Prayer prayerToActivate(Step step)
	{
		Matcher task = step == null || !ClueTypes.SKILL.equals(step.getType()) || step.getText() == null ? null
			: ACTIVATE_PRAYER.matcher(step.getText().toLowerCase());
		if (task == null || !task.matches())
		{
			return null;
		}
		return Arrays.stream(Prayer.values()).filter(prayer -> prayer.name().equalsIgnoreCase(task.group(1))).findFirst().orElse(null);
	}

	/** True while the step still has its task to do, before core marks it completed. */
	public static boolean taskLeft(Step step)
	{
		return step.getStages().stream().anyMatch(stage -> stage.getKind() == Stage.Kind.TASK);
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

	private static Map<WorldPoint, List<StepIndex.Alternative>> usesByTile(List<StepIndex.Place> places)
	{
		return places.stream().filter(place -> !place.getUses().isEmpty())
			.collect(Collectors.toUnmodifiableMap(StepIndex.Place::getTile, StepIndex.Place::getUses));
	}

	private static Set<Integer> npcsOf(List<StepIndex.Place> places)
	{
		return places.stream().flatMap(place -> place.getNpcs().stream()).collect(Collectors.toUnmodifiableSet());
	}

	private static List<WorldPoint> placeTiles(List<StepIndex.Place> places)
	{
		return places.stream().map(StepIndex.Place::getTile).distinct().collect(Collectors.toUnmodifiableList());
	}
}
