package com.cluehelper.guide;

import com.cluehelper.Tables;
import com.cluehelper.step.*;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;
import java.util.*;
import net.runelite.api.coords.WorldPoint;
import static java.util.stream.Collectors.*;
import com.cluehelper.guide.SubStep.Kind;

/**
 * The acts {@code StepLoader} bakes into each entry of {@code steps.json}, built as {@link Acts} built them at play before
 * the bake: {@code acts} is what {@link #actsOf} gives with nothing from the STASH and no hand-in names, {@code mainAct}
 * what {@link #act} gives with no marker. Acts keeps the parts that depend on the player.
 */
public final class ActParts
{
	private static final String STAY_EQUIPPED = "Stay equipped until Uri is done";
	private static final String TALK_OPTION = "Talk-to";
	private static final String ATTACK_OPTION = "Attack";
	static final String USE_OPTION = "Use";
	/** By ids: core doesn't mark Uri, and a target by name is left to core while the clue's tile is in view. */
	private static final Set<Integer> URI_IDS = Tables.idSet("uri");
	private static final Set<String> TYPES_WITH_OWN_ACT = Set.of(ClueTypes.EMOTE, ClueTypes.MUSIC, ClueTypes.HOT_COLD, ClueTypes.SKILL);

	private ActParts()
	{
	}

	/** The acts as the entry holds them: an emote's after its prefix, none for a skill challenge's hand-in. */
	public static List<SubStep> baked(Step step)
	{
		List<SubStep> acts = actsOf(step, false, List.of());
		if (ClueTypes.SKILL.equals(step.getType()))
		{
			return List.of();
		}
		if (ClueTypes.EMOTE.equals(step.getType()))
		{
			acts = acts.subList(acts.size() - 2, acts.size());
		}
		return acts.stream().map(act -> withNpcNameOut(step, act)).collect(toUnmodifiableList());
	}

	public static SubStep bakedMain(Step step)
	{
		return withNpcNameOut(step, act(step));
	}

	/** Who a walk to an area step's targets ends on: the first act's NPCs. */
	public static Set<Integer> arriveNpcs(Step step)
	{
		return step.getData().getArea() == null ? Set.of() : actsOf(step, false, List.of()).stream()
			.filter(sub -> sub.getKind() == Kind.ACT && !sub.getNpcIds().isEmpty()).map(SubStep::getNpcIds).findFirst().orElse(Set.of());
	}

	/** Core's name for the step's NPC as {@link Step#NPC_NAME}, which {@link Acts} names again at play. */
	private static SubStep withNpcNameOut(Step step, SubStep act)
	{
		if (step.getNpcs().isEmpty())
		{
			return act;
		}
		String npc = step.getNpcs().get(0);
		String menuName = MenuLabels.normalise(npc);
		return act.toBuilder().text(act.getText().replace(npc, Step.NPC_NAME))
			.detail(act.getDetail() == null ? null : act.getDetail().replace(npc, Step.NPC_NAME))
			.npcNames(act.getNpcNames().stream().map(name -> name.equals(menuName) ? Step.NPC_NAME : name).collect(toUnmodifiableSet()))
			.build();
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

	/** The step's one act: its marker's act, a key search, or what its type does. */
	static SubStep act(Step step)
	{
		if (step.getWaypointAct() != null)
		{
			return SubStep.of(Kind.ACT, step.getWaypointAct())
				.waypoint(step.isActAnywhere() ? null : step.getTargets().get(0))
				.actItems(step.getActItems()).build();
		}
		if (step.isKeyFromNpc())
		{
			String what = hasAny(step.getObjectNames()) ? "the " + step.getObjectNames().get(0).toLowerCase() : "it";
			return SubStep.of(Kind.ACT, "Search " + what + " with the key")
				.detail("The key comes from " + step.getNpcs().get(0)).build();
		}
		SubStep.SubStepBuilder act = SubStep.of(Kind.ACT, actText(step));
		String npc = talkedTo(step);
		String option = npc == null ? null : step.getData().getNpcOption();
		if (option != null)
		{
			return act.text(option + " with " + npc).option(option).npcNames(Set.of(MenuLabels.normalise(npc)))
				.npcIds(targetNpcs(step)).build();
		}
		// Core names some talking objects as NPCs (Juna, the Tears of Guthix's guardian): the cache's object wins
		String object = talkedToObject(step);
		if (object != null)
		{
			Set<Integer> objects = new HashSet<>(step.getObjectIds());
			step.getData().getTargets().forEach(target -> objects.addAll(target.getLocs()));
			return act.text("Talk to " + (object.equals(npc) ? object : "the " + object.toLowerCase())).option(TALK_OPTION)
				.objectIds(Set.copyOf(objects)).build();
		}
		return npc == null ? act.build() : talkTo(act, npc).npcIds(targetNpcs(step)).build();
	}

	/** The object the step's act talks to (the spirit tree); null for a step whose act is something else. */
	private static String talkedToObject(Step step)
	{
		boolean ownAct = TYPES_WITH_OWN_ACT.contains(step.getType());
		return ownAct ? null : step.getData().getTargets().stream().map(StepIndex.Target::getTalkTo).filter(Objects::nonNull)
			.findFirst().orElse(null);
	}

	/** By id too: a ghost goes by another name in the game than in the clue (Viggora is a Mysterious ghost). */
	private static Set<Integer> targetNpcs(Step step)
	{
		return step.getData().getTargets().stream().flatMap(target -> target.getNpcs().stream()).collect(toUnmodifiableSet());
	}

	/** The NPC the step's own act talks to; null for a step whose act is something else. */
	private static String talkedTo(Step step)
	{
		boolean ownAct = TYPES_WITH_OWN_ACT.contains(step.getType());
		return !ownAct && !isDig(step) && hasAny(step.getNpcs()) ? step.getNpcs().get(0) : null;
	}

	private static boolean hasAny(List<?> list)
	{
		return list != null && !list.isEmpty();
	}

	private static String actText(Step step)
	{
		String type = step.getType() == null ? "" : step.getType();
		switch (type)
		{
			case ClueTypes.EMOTE:
				return "Perform the emote";
			case ClueTypes.MUSIC:
				return "Talk to Cecilia";
			case ClueTypes.HOT_COLD:
				return "Feel the device, then dig";
			case ClueTypes.SKILL:
				return "Do the challenge";
			default:
				break;
		}
		if (isDig(step))
		{
			return "Dig";
		}
		if (hasAny(step.getNpcs()))
		{
			return "Talk to " + step.getNpcs().get(0);
		}
		if (hasAny(step.getObjectNames()))
		{
			return "Search the " + step.getObjectNames().get(0).toLowerCase();
		}
		if (hasAny(step.getObjectIds()))
		{
			return "Search it";
		}
		return "Do the clue step";
	}

	static List<SubStep> actsOf(Step step, boolean takeFromStash, List<String> handInItems)
	{
		SubStep main = act(step);
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
				return List.of(useOnFalo(handInItems).option(USE_OPTION).npcIds(targetNpcs(step)).build());
			case ClueTypes.SKILL:
				return List.of(handIn(step, handInItems));
			default:
				break;
		}
		StepIndex.Entry data = step.getData();
		StepIndex.Target kill = data == null ? null : data.getTargets().stream()
			.filter(target -> target.getKind() == StepIndex.Target.Kind.KILL).findFirst().orElse(null);
		if (kill != null)
		{
			String text = kill.getText() != null ? kill.getText() : "Kill the monster";
			return List.of(SubStep.of(Kind.ACT, text).detail(area(data)).option(ATTACK_OPTION)
				.npcIds(Set.copyOf(kill.getNpcs())).build());
		}
		if (isDig(step))
		{
			return List.of(main.toBuilder().text("Dig").detail(withFloor(step, note(step))).fights(fightsFor(step)).again("Dig again").build());
		}
		if (data != null && data.getChallenge() != null && !step.getNpcs().isEmpty())
		{
			return talkThenChallenge(step, data.getChallenge());
		}
		return List.of(main.toBuilder().detail(withFloor(step, main.getDetail())).build());
	}

	private static List<SubStep> emote(Step step, boolean takeFromStash)
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
		String emotes = step.getEmotes().isEmpty() ? "the emote" : String.join(", then ", step.getEmotes());
		acts.add(SubStep.of(Kind.ACT, "Perform " + emotes).detail(items ? STAY_EQUIPPED : null)
			.done(SubStep.Done.URI_HERE).fights(fightsFor(step)).again("Perform " + emotes + " again").tab(StepGuide.Tab.EMOTES).build());
		acts.add(SubStep.of(Kind.ACT, "Talk to Uri").detail(items ? "Stay equipped" : null)
			.option(TALK_OPTION).npcIds(URI_IDS).build());
		return List.copyOf(acts);
	}

	/** Take the items out of the STASH unit and put them on. */
	static List<SubStep> fromStash(Step step)
	{
		SubStep take = SubStep.of(Kind.ACT, "Search the STASH unit: take the items")
			.detail("Then equip them").done(SubStep.Done.STASH_WITHDRAWN).build();
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

	private static SubStep music(Step step)
	{
		StepIndex.Music music = step.getData().getMusic();
		String track = music != null && music.getTrack() != null ? music.getTrack() : step.getText();
		String unlock = music == null || music.getUnlock() == null ? null : "Unlocked: " + music.getUnlock();
		return act("Talk to Cecilia, then play " + track, unlock == null ? "Music tab" : "Music tab. " + unlock).toBuilder()
			.tab(StepGuide.Tab.MUSIC).build();
	}

	private static SubStep.SubStepBuilder useOnFalo(List<String> handInItems)
	{
		return handInItems.isEmpty() ? SubStep.of(Kind.ACT, "Use the item on Falo the Bard").detail("The item the Clue Scroll plugin lists")
			: SubStep.of(Kind.ACT, "Use on Falo the Bard:").items(handInItems);
	}

	/** Charlie's item is listed by name, as the withdraw and equip steps list theirs. */
	private static SubStep handIn(Step step, List<String> handInItems)
	{
		if (!"beginner".equals(step.getTier()))
		{
			return act("Talk to Sherlock", "Task done: hand it in");
		}
		return handInItems.isEmpty() ? act("Give Charlie the item", "He wants what the clue names")
			: SubStep.of(Kind.ACT, "Give Charlie:").items(handInItems).build();
	}

	private static List<SubStep> talkThenChallenge(Step step, StepIndex.Challenge challenge)
	{
		String npc = step.getNpcs().get(0);
		SubStep talk = talkTo(SubStep.of(Kind.ACT, "Talk to " + npc).detail(withFloor(step, null))
			.done(SubStep.Done.CHALLENGE_ITEM), npc).build();
		if (challenge.getBox() != null)
		{
			String box = challenge.getBox().toLowerCase().contains("light") ? "light box" : "puzzle box";
			return List.of(talk, openBoxThenTalk(SubStep.builder().kind(Kind.ACT), box, npc));
		}
		return List.of(talk, talkTo(act("Talk to " + npc + " again: answer the challenge", null).toBuilder(), npc).build());
	}

	/** The box the talk handed over is opened (core solves it), then the NPC is talked to again; {@code act} is the talk, or a new act. */
	static SubStep openBoxThenTalk(SubStep.SubStepBuilder act, String box, String npc)
	{
		return act.text("Open the " + box + ", then talk to " + npc + " again").actItemNamed(box).build();
	}

	private static SubStep.SubStepBuilder talkTo(SubStep.SubStepBuilder act, String npc)
	{
		return act.option(TALK_OPTION).npcNames(Set.of(MenuLabels.normalise(npc)));
	}

	private static SubStep act(String text, String detail)
	{
		return SubStep.of(Kind.ACT, text).detail(detail).build();
	}

	private static boolean isDig(Step step)
	{
		return step.getRequirements().getSpade() != SpadeNeed.NONE;
	}

	private static boolean fightsFor(Step step)
	{
		return !step.getData().getCombat().isEmpty();
	}

	private static String note(Step step)
	{
		return step.getData().getNote();
	}

	private static String area(StepIndex.Entry data)
	{
		return data.getArea() == null ? null : data.getArea().getText();
	}

	/**
	 * Core's tile can be on another floor than the NPC (Donovan upstairs, the cache's tile on floor 1): hand data says
	 * so, else the cache's plane does.
	 */
	private static String withFloor(Step step, String detail)
	{
		String floor = step.getData().getFloor();
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
