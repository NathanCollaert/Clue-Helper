package com.cluehelper.guide;

import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.EmptySlots;
import com.cluehelper.step.SpadeNeed;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepIndex;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * What to do once at the step, per type: the sub-steps after the travel. Texts name what core doesn't show in the same
 * words (the emotes in order, the hand-in) and point to core for the rest; highlights stay core's.
 */
final class Acts
{
	private static final String STAY_EQUIPPED = "Stay equipped until Uri is done";

	private Acts()
	{
	}

	/**
	 * Gear the act wants worn (Robin's +180 ranged attack) goes on first; a step of several places wears it per stage.
	 *
	 * @param takeFromStash the step's items are in its STASH unit and not on the player
	 */
	static List<SubStep> of(Step step, boolean takeFromStash)
	{
		List<String> worn = step.getData() == null || !step.getStages().isEmpty() ? List.of() : step.getData().getWornNeeds();
		List<SubStep> acts = actsOf(step, takeFromStash);
		if (worn.isEmpty())
		{
			return acts;
		}
		List<SubStep> equipped = new ArrayList<>(SubSteps.equip(worn));
		equipped.addAll(acts);
		return List.copyOf(equipped);
	}

	private static List<SubStep> actsOf(Step step, boolean takeFromStash)
	{
		SubStep main = SubSteps.act(step);
		if (step.getWaypointAct() != null)
		{
			return List.of(main);
		}
		String type = step.getType() == null ? "" : step.getType();
		switch (type)
		{
			case ClueTypes.EMOTE:
				return emote(step, takeFromStash);
			case ClueTypes.MUSIC:
				return List.of(music(step));
			case ClueTypes.FALO:
				return List.of(act("Talk to Falo the Bard", "With the item the Clue Scroll plugin lists"));
			case ClueTypes.SKILL:
				return List.of(handIn(step));
			default:
				break;
		}
		StepIndex.Entry data = step.getData();
		StepIndex.Target kill = data == null ? null : data.getTargets().stream()
			.filter(target -> target.getKind() == StepIndex.Target.Kind.KILL).findFirst().orElse(null);
		if (kill != null)
		{
			return List.of(act(kill.getText() != null ? kill.getText() : "Kill the monster", area(data)));
		}
		if (isDig(step))
		{
			return List.of(main.toBuilder().text("Dig").detail(withFloor(step, note(step))).fights(fightsFor(step)).again("Dig again").build());
		}
		if (data != null && data.getChallenge() != null && step.getNpcs() != null && !step.getNpcs().isEmpty())
		{
			return talkThenChallenge(step, data.getChallenge());
		}
		return List.of(main.toBuilder().detail(withFloor(step, main.getDetail())).build());
	}

	private static List<SubStep> emote(Step step, boolean takeFromStash)
	{
		List<SubStep> acts = new ArrayList<>();
		WorldPoint stash = takeFromStash ? SubSteps.stashTile(step) : null;
		boolean items = step.getRequirements() != null && !step.getRequirements().getItems().isEmpty();
		acts.addAll(items ? SubSteps.remove(EmptySlots.of(step.getRequirements().getItems())) : List.of());
		acts.addAll(takeFromStash ? fromStash(step) : items ? List.of(equip()) : List.of());
		WorldPoint spot = step.getTargets().isEmpty() ? null : step.getTargets().get(0);
		if (stash != null && spot != null)
		{
			acts.add(SubStep.builder().kind(SubStep.Kind.WALK_TO_CLUE).text("Walk to the clue")
				.goal(spot).radius(SubSteps.ARRIVE_TILES).build());
		}
		String emotes = step.getEmotes().isEmpty() ? "the emote" : String.join(", then ", step.getEmotes());
		acts.add(SubStep.builder().kind(SubStep.Kind.ACT).text("Perform " + emotes).detail(items ? STAY_EQUIPPED : null)
			.done(SubStep.Done.URI_HERE).fights(fightsFor(step)).again("Perform " + emotes + " again").tab(StepGuide.Tab.EMOTES).build());
		acts.add(SubStep.builder().kind(SubStep.Kind.ACT).text("Talk to Uri").detail(items ? "Stay equipped" : null).build());
		return List.copyOf(acts);
	}

	/** Take the items out of the STASH unit and put them on. */
	static List<SubStep> fromStash(Step step)
	{
		SubStep take = SubStep.builder().kind(SubStep.Kind.ACT).text("Search the STASH unit: take the items")
			.detail("Then equip them").done(SubStep.Done.STASH_WITHDRAWN).build();
		boolean items = step.getRequirements() != null && !step.getRequirements().getItems().isEmpty();
		return items ? List.of(take, equip()) : List.of(take);
	}

	private static SubStep equip()
	{
		return SubStep.builder().kind(SubStep.Kind.ACT).text("Equip the items").detail("The Clue Scroll plugin lists them")
			.done(SubStep.Done.EQUIPPED).tab(StepGuide.Tab.INVENTORY).build();
	}

	private static SubStep music(Step step)
	{
		StepIndex.Music music = step.getData() == null ? null : step.getData().getMusic();
		String track = music != null && music.getTrack() != null ? music.getTrack() : step.getText();
		String unlock = music == null || music.getUnlock() == null ? null : "Unlocked: " + music.getUnlock();
		return act("Talk to Cecilia, then play " + track, unlock == null ? "Music tab" : "Music tab. " + unlock).toBuilder()
			.tab(StepGuide.Tab.MUSIC).build();
	}

	private static SubStep handIn(Step step)
	{
		boolean charlie = "beginner".equals(step.getTier());
		return act(charlie ? "Give Charlie the item" : "Talk to Sherlock", charlie ? "He wants what the clue names" : "Task done: hand it in");
	}

	private static List<SubStep> talkThenChallenge(Step step, StepIndex.Challenge challenge)
	{
		String npc = step.getNpcs().get(0);
		SubStep talk = SubStep.builder().kind(SubStep.Kind.ACT).text("Talk to " + npc).detail(withFloor(step, null))
			.done(SubStep.Done.CHALLENGE_ITEM).build();
		if (challenge.getBox() != null)
		{
			String box = challenge.getBox().toLowerCase().contains("light") ? "light box" : "puzzle box";
			return List.of(talk, act("Open the " + box + ", then talk to " + npc + " again", null).toBuilder().actItemNamed(box).build());
		}
		return List.of(talk, act("Talk to " + npc + " again: answer the challenge", null));
	}

	private static SubStep act(String text, String detail)
	{
		return SubStep.builder().kind(SubStep.Kind.ACT).text(text).detail(detail).build();
	}

	private static boolean isDig(Step step)
	{
		return step.getRequirements() != null && step.getRequirements().getSpade() != SpadeNeed.NONE;
	}

	private static boolean fightsFor(Step step)
	{
		return step.getData() != null && !step.getData().getCombat().isEmpty();
	}

	private static String note(Step step)
	{
		return step.getData() == null ? null : step.getData().getNote();
	}

	private static String area(StepIndex.Entry data)
	{
		return data.getArea() == null ? null : data.getArea().getText();
	}

	/**
	 * Core's tile can be on another floor than the NPC (Donovan upstairs, the cache's tile on floor 1): hand data says
	 * so, else the cache's plane does.
	 */
	static String withFloor(Step step, String detail)
	{
		String floor = step.getData() == null ? null : step.getData().getFloor();
		if (floor == null && !step.getTargets().isEmpty())
		{
			int corePlane = step.getTargets().get(0).getPlane();
			for (WorldPoint other : step.getOtherTiles())
			{
				if (other.getPlane() > corePlane && other.distanceTo2D(step.getTargets().get(0)) <= SubSteps.AREA_ARRIVE_TILES)
				{
					floor = other.getPlane() == 1 ? "Upstairs: first floor" : "Upstairs: floor " + other.getPlane();
				}
			}
		}
		if (floor == null)
		{
			return detail;
		}
		return detail == null ? floor : floor + ". " + detail;
	}
}
