package com.cluehelper.step;

import com.cluehelper.guide.*;
import com.cluehelper.player.*;
import java.util.*;
import java.util.regex.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.SkillChallengeClue;
import static java.util.stream.Collectors.*;

/**
 * The stages a step's entry gives it, as {@code StepLoader} bakes them into {@code steps.json}: a kill for a key or for a
 * way in's item, or a Sherlock task, each before a {@code FINAL} stage the resolver gives core's targets, and a master
 * cryptic's stage as a three-step's part. What only the game knows is left to the resolver: the key NPC's name
 * ({@link Step#NPC_NAME}), the stage's item (from {@code itemIds}) and the task's empty-slot needs.
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
	/** A task's place that reads as the walk's end ("The nearest anvil"), else what the stage is done on ("the nearest black warlock"). */
	private static final Pattern SHORT_PLACE = Pattern.compile("(The|An?) [^:,(.]+");
	/** A tile no place is at: the walk line a leg ending off the places gets. */
	private static final WorldPoint OFF_THE_PLACES = new WorldPoint(0, 0, 0);

	private StageParts()
	{
	}

	/** The stages with the line of the walk to each of our markers. */
	static List<Stage> of(StepIndex.Entry entry, StepSource.Entry source, ClueScroll clue)
	{
		return stagesOf(entry, source, clue).stream().map(StageParts::withWalk).collect(toUnmodifiableList());
	}

	/**
	 * A master cryptic as one part of a three-step: its act at its first target, which is outlined. Its NPC is named as
	 * core names it, but where {@code nameIsLive} (a name that changes with a quest): {@link Step#NPC_NAME} stands for it.
	 */
	static Stage part(StepIndex.Entry entry, String coreNpc, boolean nameIsLive)
	{
		StepIndex.Target target = entry.getTargets().get(0);
		String name = target.getNpcs().isEmpty() ? target.getTalkTo() : coreNpc;
		Step named = Step.builder().npcs(name == null ? List.of() : List.of(NpcAliases.either(name))).build();
		SubStep act = entry.getMainAct();
		boolean live = nameIsLive && !target.getNpcs().isEmpty();
		return Stage.builder().kind(Stage.Kind.PART).act(live ? act.getText() : named.namingNpc(act.getText()))
			.option(act.getOption()).tiles(List.of(entry.getStand() != null ? entry.getStand() : target.getTile()))
			.worn(entry.getWornNeeds()).npcIds(Set.copyOf(target.getNpcs())).objectIds(target.getLocs()).build();
	}

	/** In the order the resolver tried them: a key, then a dropped way-in item, then a Sherlock challenge. */
	private static List<Stage> stagesOf(StepIndex.Entry entry, StepSource.Entry source, ClueScroll clue)
	{
		List<StepIndex.Target> targets = entry.getTargets();
		for (int i = 0; i < targets.size(); i++)
		{
			if (targets.get(i).getKind() == StepIndex.Target.Kind.KEY && targets.get(i).getKeyTile() != null)
			{
				return killForKey(targets.get(i), source.getTargets().get(i).getText(), source.getKeyAlso());
			}
		}
		List<StepIndex.Requires> requires = entry.getRequires();
		for (int i = 0; i < requires.size(); i++)
		{
			if (requires.get(i).getFrom() != null)
			{
				return killForWayIn(requires.get(i), source.getRequires().get(i).getFrom());
			}
		}
		if (clue instanceof SkillChallengeClue)
		{
			return challenge(entry, source, ((SkillChallengeClue) clue).getRawChallenge(), CoreClueReader.returnItem(clue) != null);
		}
		return List.of();
	}

	/** Kill one of the key's NPCs where the cache says, then search the step's own object. */
	private static List<Stage> killForKey(StepIndex.Target key, String howTo, List<WorldPoint> also)
	{
		List<WorldPoint> tiles = new ArrayList<>(List.of(key.getKeyTile()));
		tiles.addAll(also);
		Stage kill = Stage.builder().kind(Stage.Kind.KEY).act(keyAct(howTo)).npcIds(Set.copyOf(key.getNpcs())).option(ATTACK)
			.tiles(List.copyOf(tiles)).ourMarker(true).radius(KEY_AREA_TILES).itemIds(List.of(key.getKey())).build();
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
	private static List<Stage> killForWayIn(StepIndex.Requires requires, StepSource.From from)
	{
		Stage kill = Stage.builder().kind(Stage.Kind.KEY).act(from.getText()).tiles(List.of(from.getTile())).ourMarker(true)
			.radius(KEY_AREA_TILES).itemIds(requires.getIds()).itemName(requires.getName()).npcIds(Set.copyOf(from.getNpcs())).objectIds(Set.copyOf(from.getObjects()))
			.option(from.getOption() == null ? ATTACK : from.getOption())
			.skippedWhenOwned(Set.copyOf(requires.getIds())).build();
		return List.of(kill, Stage.builder().kind(Stage.Kind.FINAL).tiles(CORE_TARGETS).build());
	}

	/** A stage ending on our marker, with the walk's line there, which must read the same at every one of its tiles. */
	private static Stage withWalk(Stage stage)
	{
		if (!stage.isOurMarker())
		{
			return stage;
		}
		String targetName = targetName(stage);
		List<WorldPoint> ends = new ArrayList<>(stage.getTiles());
		ends.add(OFF_THE_PLACES);
		Set<String> walks = ends.stream().map(tile -> walk(stage, targetName, tile)).collect(toSet());
		if (walks.size() != 1)
		{
			throw new IllegalStateException("the walk to a stage reads differently by tile, which the plugin can't show: " + walks);
		}
		return stage.toBuilder().walk(walks.iterator().next()).build();
	}

	/** By the task's place where it reads as one ("Walk to an allotment patch"), else by what the stage is done on. */
	private static String walk(Stage stage, String targetName, WorldPoint to)
	{
		String place = stage.getKind() == Stage.Kind.TASK ? stage.atPlace(to).getDetail() : null;
		if (place != null && SHORT_PLACE.matcher(place).matches())
		{
			return "Walk to " + Character.toLowerCase(place.charAt(0)) + place.substring(1);
		}
		if (targetName != null)
		{
			return "Walk to the nearest " + targetName;
		}
		return !stage.getNpcIds().isEmpty() ? "Walk to the outlined NPC"
			: !stage.getObjectIds().isEmpty() ? "Walk to the outlined object" : "Walk to the marker";
	}

	/**
	 * The one name the stage's objects and NPCs share in the cache, lower-cased as a common noun ("anvil", "black
	 * warlock", "rod fishing spot") unless every word of several is capitalised ("Mercenary Captain"); null when they go
	 * by several.
	 */
	private static String targetName(Stage stage)
	{
		Set<String> names = new TreeSet<>();
		stage.getObjectIds().forEach(id -> names.add(TargetNames.OBJECTS.getOrDefault(id, NO_NAME)));
		stage.getNpcIds().forEach(id -> names.add(TargetNames.NPCS.getOrDefault(id, NO_NAME)));
		names.remove(NO_NAME);
		if (names.size() != 1)
		{
			return null;
		}
		String name = names.iterator().next();
		String common = name.toLowerCase(Locale.ROOT);
		String one = common.endsWith(ROCKS) ? common.substring(0, common.length() - 1) : common;
		return PROPER_NAME.matcher(name).matches() ? name : one;
	}

	/** One place per distinct area: the plugin's places, which hold only their objects and area. */
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
	private static List<Stage> challenge(StepIndex.Entry entry, StepSource.Entry source, String taskText, boolean returnsItem)
	{
		if (returnsItem)
		{
			return List.of();
		}
		List<StepSource.Place> places = source.getPlaces();
		String where = !source.hasPlaces() ? null : places.isEmpty() ? ANYWHERE : places.get(0).getText();
		Stage task = Stage.builder().kind(Stage.Kind.TASK).act(source.getAct() == null ? sentence(taskText) : source.getAct()).detail(where)
			.tiles(placeTiles(places)).ourMarker(true).objectIds(objectsOf(places)).npcIds(npcsOf(places)).takesStepItems(true)
			.worn(source.getWear()).spans(spans(entry.getPlaces()))
			.beforeAnywhere(source.getBefore().stream().filter(StepSource.Before::isAnywhere).map(ActParts::beforeAct).collect(toUnmodifiableList()))
			.beforeThere(source.getBefore().stream().filter(before -> !before.isAnywhere()).map(ActParts::beforeAct).collect(toUnmodifiableList()))
			.option(places.stream().map(StepSource.Place::getOption).filter(option -> option != null).findFirst().orElse(null))
			.dialogue(places.stream().map(StepSource.Place::getDialogue).filter(dialogue -> !dialogue.isEmpty()).findFirst()
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

	private static Map<WorldPoint, String> placeTexts(List<StepSource.Place> places)
	{
		return places.stream().filter(place -> place.getText() != null)
			.collect(toUnmodifiableMap(StepSource.Place::getTile, StepSource.Place::getText, (first, same) -> first));
	}

	private static Map<WorldPoint, String> grownOnly(List<StepSource.Place> places)
	{
		return places.stream().filter(place -> place.getGrown() != null)
			.collect(toUnmodifiableMap(StepSource.Place::getTile, StepSource.Place::getGrown));
	}

	private static Set<Integer> objectsOf(List<StepSource.Place> places)
	{
		return places.stream().flatMap(place -> place.getObjects().stream()).collect(toUnmodifiableSet());
	}

	private static Map<WorldPoint, List<StepIndex.Alternative>> usesByTile(List<StepSource.Place> places)
	{
		return places.stream().filter(place -> !place.getUses().isEmpty())
			.collect(toUnmodifiableMap(StepSource.Place::getTile, StepSource.Place::getUses));
	}

	private static Set<Integer> npcsOf(List<StepSource.Place> places)
	{
		return places.stream().flatMap(place -> place.getNpcs().stream()).collect(toUnmodifiableSet());
	}

	private static List<WorldPoint> placeTiles(List<StepSource.Place> places)
	{
		return places.stream().map(StepSource.Place::getTile).distinct().collect(toUnmodifiableList());
	}
}
