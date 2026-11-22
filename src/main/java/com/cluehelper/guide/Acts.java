package com.cluehelper.guide;

import com.cluehelper.step.*;
import java.util.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;
import static java.util.stream.Collectors.*;
import com.cluehelper.guide.SubStep.Kind;

/**
 * What to do once at the step: the sub-steps after the travel. The step data holds them baked, by type (the test source
 * set's {@code ActParts} builds them); what depends on the player is added here: gear to wear, an emote's STASH items,
 * the items handed in, a hot/cold marker's act and core's name for the NPC.
 */
final class Acts
{
	static final String USE_OPTION = "Use";
	/** A step the data lacks: core shows what to do. */
	private static final SubStep DO_THE_STEP = SubStep.of(Kind.ACT, "Do the clue step").build();

	private Acts()
	{
	}

	/**
	 * Gear the act wants worn (Robin's +180 ranged attack) goes on first; a step of several places wears it per stage.
	 *
	 * @param takeFromStash the step's items are in its STASH unit and not on the player
	 * @param handInItems the items Charlie or Falo the Bard asks for, by name
	 */
	static List<SubStep> of(Step step, boolean takeFromStash, List<String> handInItems)
	{
		List<String> worn = !step.getStages().isEmpty() ? List.of() : step.getData().getWornNeeds();
		List<SubStep> acts = actsOf(step, takeFromStash, handInItems);
		if (worn.isEmpty())
		{
			return acts;
		}
		List<SubStep> equipped = new ArrayList<>(SubSteps.equip(worn));
		equipped.addAll(acts);
		return List.copyOf(equipped);
	}

	/** The step's one act: its marker's act, or the data's. */
	static SubStep act(Step step)
	{
		if (step.getWaypointAct() != null)
		{
			return SubStep.of(Kind.ACT, step.getWaypointAct())
				.waypoint(step.isActAnywhere() ? null : step.getTargets().get(0))
				.actItems(step.getActItems()).build();
		}
		SubStep main = step.getData().getMainAct();
		return main == null ? DO_THE_STEP : named(step, main);
	}

	private static List<SubStep> actsOf(Step step, boolean takeFromStash, List<String> handInItems)
	{
		List<SubStep> baked = step.getData().getActs().stream().map(act -> named(step, act)).collect(toUnmodifiableList());
		if (step.getWaypointAct() != null || baked.isEmpty() && !ClueTypes.SKILL.equals(step.getType()))
		{
			return List.of(act(step));
		}
		switch (step.getType())
		{
			case ClueTypes.EMOTE:
				return emote(step, takeFromStash, baked);
			case ClueTypes.FALO:
				return handInItems.isEmpty() ? baked
					: List.of(baked.get(0).toBuilder().text("Use " + String.join(" and ", inLine(handInItems)) + " on Falo the Bard").detail(null).build());
			case ClueTypes.SKILL:
				return List.of(handIn(step, handInItems));
			default:
				return baked;
		}
	}

	/** The baked act with core's name for the step's NPC, which can change with a quest or come from the client. */
	private static SubStep named(Step step, SubStep act)
	{
		if (step.getNpcs().isEmpty())
		{
			return act;
		}
		String npc = MenuLabels.normalise(step.getNpcs().get(0));
		return act.toBuilder().text(step.namingNpc(act.getText())).detail(step.namingNpc(act.getDetail()))
			.npcNames(act.getNpcNames().stream().map(name -> name.equals(Step.NPC_NAME) ? npc : name).collect(toUnmodifiableSet()))
			.build();
	}

	/** Core's items off and the step's on, from the STASH unit when they wait there, then the emotes. */
	private static List<SubStep> emote(Step step, boolean takeFromStash, List<SubStep> baked)
	{
		List<SubStep> acts = new ArrayList<>();
		WorldPoint stash = takeFromStash ? SubSteps.stashTile(step) : null;
		boolean items = !step.getRequirements().getItems().isEmpty();
		acts.addAll(items ? SubSteps.remove(EmptySlots.of(step.getRequirements().getItems())) : List.of());
		acts.addAll(takeFromStash ? fromStash(step) : items ? List.of(equip(step.getRequirements().getItems())) : List.of());
		WorldPoint spot = step.getTargets().isEmpty() ? null : step.getTargets().get(0);
		if (stash != null && spot != null)
		{
			acts.add(LegSteps.walkToClue(spot, SubSteps.ARRIVE_TILES, step.getData()));
		}
		acts.addAll(items ? withItemsBackOnForUri(step, baked) : baked);
		return List.copyOf(acts);
	}

	/** Core's names read mid-line: "Any completed god book" is "any completed god book". */
	private static List<String> inLine(List<String> names)
	{
		return names.stream().map(name -> name.startsWith("Any ") ? "a" + name.substring(1) : name).collect(toList());
	}

	/**
	 * After the emote, before Uri: the items go back on if they came off (to fight the double agent). Each act is done at
	 * once while they stay on.
	 */
	private static List<SubStep> withItemsBackOnForUri(Step step, List<SubStep> performThenTalk)
	{
		if (performThenTalk.size() < 2)
		{
			return performThenTalk;
		}
		List<ItemRequirement> items = step.getRequirements().getItems();
		List<SubStep> acts = new ArrayList<>(performThenTalk.subList(0, 1));
		acts.addAll(SubSteps.remove(EmptySlots.of(items)));
		acts.add(equip(items));
		acts.addAll(performThenTalk.subList(1, performThenTalk.size()));
		return acts;
	}

	/** Take the items out of the STASH unit and put them on. */
	static List<SubStep> fromStash(Step step)
	{
		SubStep take = SubStep.of(Kind.ACT, "Search the STASH unit: take the items").done(SubStep.Done.STASH_WITHDRAWN).build();
		boolean items = !step.getRequirements().getItems().isEmpty();
		return items ? List.of(take, equip(itemsToPutOn(step))) : List.of(take);
	}

	/** At the STASH unit only the items go on: what must come off is taken off at the clue (inside the axe hut). */
	private static List<ItemRequirement> itemsToPutOn(Step step)
	{
		return step.getRequirements().getItems().stream().filter(item -> !EmptySlots.metByEmptySlots(item)).collect(toList());
	}

	private static SubStep equip(List<ItemRequirement> items)
	{
		return SubStep.of(Kind.ACT, "Equip the items").worn(items).unwearableCarried(true)
			.done(SubStep.Done.WORN).tab(StepGuide.Tab.INVENTORY).build();
	}

	/** Charlie's item is listed by name, as the withdraw and equip steps list theirs. */
	private static SubStep handIn(Step step, List<String> handInItems)
	{
		if (!"beginner".equals(step.getTier()))
		{
			return act("Talk to Sherlock", null);
		}
		return handInItems.isEmpty() ? act("Give Charlie the item", null)
			: SubStep.of(Kind.ACT, "Give Charlie:").items(handInItems).build();
	}

	/** The box the talk handed over is opened (core solves it), then the NPC is talked to again; {@code act} is the talk, or a new act. */
	static SubStep openBoxThenTalk(SubStep.SubStepBuilder act, String box, String npc)
	{
		return act.text("Open the " + box + ", then talk to " + npc + " again").actItemNamed(box).build();
	}

	private static SubStep act(String text, String detail)
	{
		return SubStep.of(Kind.ACT, text).detail(detail).build();
	}
}
