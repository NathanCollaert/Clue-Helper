package com.cluehelper.step;

import com.cluehelper.guide.SubStep;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import net.runelite.api.Prayer;
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
	private static final Pattern ACTIVATE_PRAYER = Pattern.compile("activate the (\\w+) prayer\\.?");

	private Stages()
	{
	}

	/** Every stage's tiles in order: the step's targets. */
	public static List<WorldPoint> tiles(List<Stage> stages)
	{
		return stages.stream().flatMap(stage -> stage.getTiles().stream()).collect(toUnmodifiableList());
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

	/** A part known by its text alone, from the step data: its act at its first target, which is outlined. */
	static Stage part(StepIndex.Entry entry, IntFunction<String> npcName)
	{
		StepIndex.Target target = entry.getTargets().get(0);
		String name = target.getNpcs().isEmpty() ? target.getTalkTo() : npcName.apply(target.getNpcs().get(0));
		Step named = Step.builder().npcs(name == null ? List.of() : List.of(NpcAliases.either(name))).build();
		SubStep act = entry.getMainAct();
		return Stage.builder().kind(Stage.Kind.PART).act(act == null ? "Search it" : named.namingNpc(act.getText()))
			.option(act == null ? null : act.getOption()).tiles(List.of(entry.getStand() != null ? entry.getStand() : target.getTile()))
			.worn(entry.getWornNeeds()).npcIds(Set.copyOf(target.getNpcs())).objectIds(target.getLocs()).build();
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
}
