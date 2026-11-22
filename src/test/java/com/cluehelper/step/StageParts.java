package com.cluehelper.step;

import com.cluehelper.player.*;
import java.util.*;
import java.util.regex.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.SkillChallengeClue;
import static java.util.stream.Collectors.*;

/**
 * The stages a step's entry gives it, as {@code StepLoader} bakes them into {@code steps.json}: a kill for a key or for a
 * way in's item, or a Sherlock task, each before a {@code FINAL} stage the resolver gives core's targets. What only the
 * game knows is left to the resolver: the key NPC's name ({@link Step#NPC_NAME}), the stage's item (from
 * {@code itemIds}) and the task's empty-slot needs.
 */
final class StageParts
{
	/** Key NPCs wander over a camp, a floor or a field. */
	private static final int KEY_AREA_TILES = 15;
	private static final String ATTACK = "Attack";
	private static final String ANYWHERE = "Anywhere, with what it needs";
	private static final List<WorldPoint> CORE_TARGETS = List.of();
	/** The cache's name for an object or NPC without one. */
	private static final String NO_NAME = "null";
	/** The cache names a mining rock in the plural ("Runite rocks"); the walk leads to one. */
	private static final String ROCKS = " rocks";
	/** "Kill any Hill Giant for a medium key", "Kill Penda in the Toad and Chicken": who, up to where or why. */
	private static final Pattern KEY_KILL = Pattern.compile("Kill (any |an? |the |one of the )?(.+?)(?= (?:in|at|located|around|for|to)\\b|\\.|,|$)");
	private static final Pattern PROPER_NAME = Pattern.compile("[A-Z][^ ]*( [A-Z][^ ]*)+");

	private StageParts()
	{
	}

	/** The stages with the name of what each is done on, for the walk there. */
	static List<Stage> of(StepIndex.Entry entry, ClueScroll clue)
	{
		return stagesOf(entry, clue).stream().map(StageParts::named).collect(toUnmodifiableList());
	}

	/** In the order the resolver tried them: a key, then a dropped way-in item, then a Sherlock challenge. */
	private static List<Stage> stagesOf(StepIndex.Entry entry, ClueScroll clue)
	{
		StepIndex.Target key = entry.getTargets().stream()
			.filter(target -> target.getKind() == StepIndex.Target.Kind.KEY && target.getKeyTile() != null).findFirst().orElse(null);
		if (key != null)
		{
			return killForKey(key);
		}
		StepIndex.Requires dropped = entry.getRequires().stream().filter(requires -> requires.getFrom() != null).findFirst().orElse(null);
		if (dropped != null)
		{
			return killForWayIn(dropped);
		}
		if (clue instanceof SkillChallengeClue)
		{
			return challenge(entry, ((SkillChallengeClue) clue).getRawChallenge(), CoreClueReader.returnItem(clue) != null);
		}
		return List.of();
	}

	/** Kill one of the key's NPCs where the cache says, then search the step's own object. */
	private static List<Stage> killForKey(StepIndex.Target key)
	{
		Stage kill = Stage.builder().kind(Stage.Kind.KEY).act(keyAct(key.getText()))
			.tiles(List.of(key.getKeyTile())).ourMarker(true).radius(KEY_AREA_TILES).itemIds(List.of(key.getKey())).build();
		return List.of(kill, Stage.builder().kind(Stage.Kind.FINAL).tiles(CORE_TARGETS).build());
	}

	/**
	 * The kill as the line says it, from the cache's how-to: "Kill any Chicken to obtain a key" is "Kill a chicken to get a
	 * key", "Kill Penda" stays, "Kill one of the Wizards" is "Kill a wizard".
	 */
	static String keyAct(String text)
	{
		Matcher kill = KEY_KILL.matcher(text == null ? "" : text);
		if (!kill.find())
		{
			return "Get the key from " + Step.NPC_NAME;
		}
		String who = kill.group(2).trim();
		String quantifier = kill.group(1) == null ? "" : kill.group(1).trim().toLowerCase(Locale.ROOT);
		if (quantifier.equals("the"))
		{
			return "Kill the " + who + " to get a key";
		}
		if (quantifier.isEmpty())
		{
			return "Kill " + who + " to get a key";
		}
		String one = (quantifier.equals("one of the") && who.endsWith("s") ? who.substring(0, who.length() - 1) : who).toLowerCase(Locale.ROOT);
		return "Kill " + (one.matches("[aeiou].*") ? "an " : "a ") + one + " to get a key";
	}

	/**
	 * Kill the NPC that drops the way in's item, then go in: skipped while the player owns the item anywhere, as the way
	 * in's requires then bring it.
	 */
	private static List<Stage> killForWayIn(StepIndex.Requires requires)
	{
		StepIndex.From from = requires.getFrom();
		Stage kill = Stage.builder().kind(Stage.Kind.KEY).act(from.getText()).tiles(List.of(from.getTile())).ourMarker(true)
			.radius(KEY_AREA_TILES).itemIds(requires.getIds()).itemName(requires.getName()).npcIds(Set.copyOf(from.getNpcs())).objectIds(Set.copyOf(from.getObjects()))
			.option(from.getOption() == null ? ATTACK : from.getOption())
			.skippedWhenOwned(Set.copyOf(requires.getIds())).build();
		return List.of(kill, Stage.builder().kind(Stage.Kind.FINAL).tiles(CORE_TARGETS).build());
	}

	/**
	 * The one name the stage's objects and NPCs share in the cache, lower-cased as a common noun ("anvil", "black
	 * warlock", "rod fishing spot") unless every word of several is capitalised ("Mercenary Captain"); none when they go
	 * by several.
	 */
	private static Stage named(Stage stage)
	{
		Set<String> names = new TreeSet<>();
		stage.getObjectIds().forEach(id -> names.add(TargetNames.OBJECTS.getOrDefault(id, NO_NAME)));
		stage.getNpcIds().forEach(id -> names.add(TargetNames.NPCS.getOrDefault(id, NO_NAME)));
		names.remove(NO_NAME);
		if (names.size() != 1)
		{
			return stage;
		}
		String name = names.iterator().next();
		String common = name.toLowerCase(Locale.ROOT);
		String one = common.endsWith(ROCKS) ? common.substring(0, common.length() - 1) : common;
		return stage.toBuilder().targetName(PROPER_NAME.matcher(name).matches() ? name : one).build();
	}

	/** One place per distinct area. */
	private static List<StepIndex.Place> spans(List<StepIndex.Place> places)
	{
		List<StepIndex.Place> spans = new ArrayList<>();
		for (StepIndex.Place place : places)
		{
			if (place.getSpan() != null && spans.stream().noneMatch(kept -> kept.getSpan().equals(place.getSpan())))
			{
				spans.add(place);
			}
		}
		return List.copyOf(spans);
	}

	/**
	 * Sherlock: the task (at its places, or anywhere when the data has none) until core marks it completed, then
	 * Sherlock. Empty for Charlie, whose item is a need of the step like Sherlock's
	 * items: the player brings it.
	 */
	private static List<Stage> challenge(StepIndex.Entry entry, String taskText, boolean returnsItem)
	{
		if (returnsItem)
		{
			return List.of();
		}
		List<StepIndex.Place> places = entry.getPlaces();
		String where = !entry.hasPlaces() ? null : places.isEmpty() ? ANYWHERE : places.get(0).getText();
		Stage task = Stage.builder().kind(Stage.Kind.TASK).act(entry.getAct() == null ? sentence(taskText) : entry.getAct()).detail(where)
			.tiles(placeTiles(places)).ourMarker(true).objectIds(objectsOf(places)).npcIds(npcsOf(places)).takesStepItems(true)
			.worn(entry.getWear()).before(entry.getBefore()).spans(spans(places))
			.option(places.stream().map(StepIndex.Place::getOption).filter(option -> option != null).findFirst().orElse(null))
			.dialogue(places.stream().map(StepIndex.Place::getDialogue).filter(dialogue -> !dialogue.isEmpty()).findFirst()
				.orElse(List.of()))
			.grownOnly(grownOnly(places))
			.placeTexts(placeTexts(places))
			.houseObjects(places.stream().filter(place -> PositionRules.POH_NODE.equals(place.getTile()))
				.flatMap(place -> place.getObjects().stream()).collect(toUnmodifiableSet()))
			.uses(usesByTile(places))
			.book(entry.getBook() == null ? null : Book.valueOf(entry.getBook())).build();
		return List.of(task, Stage.builder().kind(Stage.Kind.FINAL).tiles(CORE_TARGETS).build());
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

	private static Map<WorldPoint, String> placeTexts(List<StepIndex.Place> places)
	{
		return places.stream().filter(place -> place.getText() != null)
			.collect(toUnmodifiableMap(StepIndex.Place::getTile, StepIndex.Place::getText, (first, same) -> first));
	}

	private static Map<WorldPoint, String> grownOnly(List<StepIndex.Place> places)
	{
		return places.stream().filter(place -> place.getGrown() != null)
			.collect(toUnmodifiableMap(StepIndex.Place::getTile, StepIndex.Place::getGrown));
	}

	private static Set<Integer> objectsOf(List<StepIndex.Place> places)
	{
		return places.stream().flatMap(place -> place.getObjects().stream()).collect(toUnmodifiableSet());
	}

	private static Map<WorldPoint, List<StepIndex.Alternative>> usesByTile(List<StepIndex.Place> places)
	{
		return places.stream().filter(place -> !place.getUses().isEmpty())
			.collect(toUnmodifiableMap(StepIndex.Place::getTile, StepIndex.Place::getUses));
	}

	private static Set<Integer> npcsOf(List<StepIndex.Place> places)
	{
		return places.stream().flatMap(place -> place.getNpcs().stream()).collect(toUnmodifiableSet());
	}

	private static List<WorldPoint> placeTiles(List<StepIndex.Place> places)
	{
		return places.stream().map(StepIndex.Place::getTile).distinct().collect(toUnmodifiableList());
	}
}
