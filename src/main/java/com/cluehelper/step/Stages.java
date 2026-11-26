package com.cluehelper.step;

import java.util.*;
import java.util.function.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.CrypticClue;
import net.runelite.client.plugins.cluescrolls.clues.item.*;
import static java.util.stream.Collectors.*;

/**
 * The stages of steps done in several places: a three-step's parts, and any step's as the player's state leaves them. The
 * others come baked in the step data. Act texts never repeat core's solutions.
 */
public final class Stages
{
	private static final String ANYWHERE = "Anywhere, with what it needs";

	private Stages()
	{
	}

	/** Every stage's tiles in order: the step's targets. */
	public static List<WorldPoint> tiles(List<Stage> stages)
	{
		return stages.stream().flatMap(stage -> stage.getTiles().stream()).collect(toUnmodifiableList());
	}

	/** Core's unsolved parts, each with its step data entry ({@code entryOf}), null for a part the data lacks. */
	static List<Stage> parts(List<CrypticClue> parts, ClueScrollPlugin plugin, Function<CrypticClue, StepIndex.Entry> entryOf)
	{
		List<Stage> stages = new ArrayList<>();
		for (CrypticClue part : parts)
		{
			WorldPoint at = part.getLocation(plugin);
			if (at != null)
			{
				StepIndex.Entry entry = entryOf.apply(part);
				stages.add(Stage.builder().kind(Stage.Kind.PART).act(partAct(part, plugin, entry)).tiles(List.of(at))
					.worn(entry == null ? List.of() : entry.getWornNeeds()).build());
			}
		}
		return List.copyOf(stages);
	}

	/** A part known by its text alone: its baked stage, naming the NPC as it shows where a quest renames it (the doorman). */
	static Stage part(StepIndex.Entry entry, IntFunction<String> npcName)
	{
		Stage part = entry.getPart();
		if (!part.getAct().contains(Step.NPC_NAME))
		{
			return part;
		}
		String name = npcName.apply(entry.getTargets().get(0).getNpcs().get(0));
		Step named = Step.builder().npcs(name == null ? List.of() : List.of(NpcAliases.either(name))).build();
		return part.toBuilder().act(named.namingNpc(part.getAct())).build();
	}

	/** A part the data lacks is searched at an object it can't name. */
	private static String partAct(CrypticClue part, ClueScrollPlugin plugin, StepIndex.Entry entry)
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
		return entry == null ? "Search it" : entry.getMainAct().getText();
	}

	/** The key a kill-for-key stage gets: a clue's own key, which no key ring holds. */
	public static ItemRequirement keyItem(int keyId)
	{
		return ItemRequirements.item(keyId);
	}

	/** The step as the player's state leaves it: a task done anywhere once an item is owned, or under way inside its area. */
	public static Step adjusted(Step step, Predicate<Integer> owns, WorldPoint at)
	{
		return underWayInSpan(doneAnywhereWhenOwned(withoutOwnedDrops(step, owns), owns), at);
	}

	/** A kill for an item the player already owns is skipped. */
	private static Step withoutOwnedDrops(Step step, Predicate<Integer> owns)
	{
		List<Stage> left = step.getStages().stream()
			.filter(stage -> stage.getSkippedWhenOwned().stream().noneMatch(owns))
			.collect(toUnmodifiableList());
		return left.size() == step.getStages().size() ? step : withStagesLeft(step, left);
	}

	/** Only the targets the NPC stands at; all of them when the data has none for it. */
	public static Step atNpc(Step step, int npcId)
	{
		List<WorldPoint> spots = step.getData().getTargets().stream()
			.filter(target -> target.getTile() != null && target.getNpcs().contains(npcId))
			.map(StepIndex.Target::getTile).collect(toUnmodifiableList());
		List<WorldPoint> kept = step.getTargets().stream().filter(tile -> spots.stream().anyMatch(spot -> spot.distanceTo(tile) <= 1))
			.collect(toUnmodifiableList());
		return kept.isEmpty() ? step : step.toBuilder().targets(kept).build();
	}

	/** The step with only these stages left; once only its own final stage is, the step keeps its own places alone. */
	static Step withStagesLeft(Step step, List<Stage> left)
	{
		boolean onlyFinal = left.size() == 1 && left.get(0).getKind() == Stage.Kind.FINAL;
		return step.toBuilder().stages(onlyFinal ? List.of() : List.copyOf(left)).targets(tiles(left))
			.kind(onlyFinal ? TargetKind.ANY_OF : TargetKind.SEQUENCE).build();
	}

	/** The task's places drop once the player owns one of the entry's {@code anywhereWith} items (a sacred eel). */
	private static Step doneAnywhereWhenOwned(Step step, Predicate<Integer> owns)
	{
		StepIndex.Entry entry = step.getData();
		if (entry == null || entry.getAnywhereWith().stream().noneMatch(owns))
		{
			return step;
		}
		Set<WorldPoint> places = step.getStages().stream().filter(stage -> stage.getKind() == Stage.Kind.TASK)
			.flatMap(stage -> stage.getTiles().stream()).collect(toSet());
		List<Stage> stages = step.getStages().stream().map(stage -> stage.getKind() != Stage.Kind.TASK ? stage
			: stage.toBuilder().tiles(List.of()).objectIds(Set.of()).npcIds(Set.of()).option(null).detail(ANYWHERE).build())
			.collect(toUnmodifiableList());
		return step.toBuilder().stages(stages)
			.targets(step.getTargets().stream().filter(tile -> !places.contains(tile)).collect(toUnmodifiableList())).build();
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
			.collect(toUnmodifiableList());
		return step.toBuilder().stages(stages)
			.targets(step.getTargets().stream().filter(tile -> !task.getTiles().contains(tile)).collect(toUnmodifiableList()))
			.build();
	}

	/** True while the step still has its task to do, before core marks it completed. */
	public static boolean taskLeft(Step step)
	{
		return step.getStages().stream().anyMatch(stage -> stage.getKind() == Stage.Kind.TASK);
	}
}
