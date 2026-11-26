package com.cluehelper.guide;

import com.cluehelper.Tables;
import com.cluehelper.step.*;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.EmoteClue;
import net.runelite.client.plugins.cluescrolls.clues.emote.Emote;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;
import com.cluehelper.guide.StepGuide.Tab;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;
import net.runelite.api.Prayer;
import net.runelite.api.coords.WorldPoint;
import static java.util.stream.Collectors.*;
import com.cluehelper.guide.SubStep.Kind;

/**
 * The acts {@code StepLoader} bakes into each entry of {@code steps.json}, built as {@link Acts} built them at play before
 * the bake: {@code acts} is what {@link #actsOf} gives with nothing from the STASH and no hand-in names, {@code mainAct}
 * what {@link #act} gives with no marker, a search of an object the clue doesn't name named by the cache. Acts keeps the
 * parts that depend on the player.
 */
public final class ActParts
{
	private static final String TALK_OPTION = "Talk-to";
	private static final String ATTACK_OPTION = "Attack";
	static final String USE_OPTION = "Use";
	/** A search of an object the clue doesn't name. */
	private static final String SEARCH_IT = "Search it";
	/** By ids: core doesn't mark Uri, and a target by name is left to core while the clue's tile is in view. */
	private static final Set<Integer> URI_IDS = Tables.idSet("uri");
	private static final Set<String> TYPES_WITH_OWN_ACT = Set.of(ClueTypes.EMOTE, ClueTypes.MUSIC, ClueTypes.HOT_COLD, ClueTypes.SKILL);
	/** Core's emotes of each emote step in order, by key: the plugin has them only in these baked acts. */
	private static final Map<String, List<String>> EMOTES = emotesByKey();
	private static final Pattern ACTIVATE_PRAYER = Pattern.compile("activate the (\\w+) prayer\\.?");

	private ActParts()
	{
	}

	/** The acts as the entry holds them: an emote's after its prefix, none for a skill challenge's hand-in. */
	public static List<SubStep> baked(Step step, StepSource.Entry source)
	{
		List<SubStep> acts = actsOf(step, source, false);
		if (ClueTypes.SKILL.equals(step.getType()))
		{
			return List.of();
		}
		if (ClueTypes.EMOTE.equals(step.getType()))
		{
			acts = acts.subList(acts.size() - 2, acts.size());
		}
		return acts.stream().map(act -> withNpcNameOut(step, searchNamed(step, act))).collect(toUnmodifiableList());
	}

	public static SubStep bakedMain(Step step, StepSource.Entry source)
	{
		return withNpcNameOut(step, searchNamed(step, act(step, source)));
	}

	/** Who a walk to an area step's targets ends on: the first act's NPCs. */
	public static Set<Integer> arriveNpcs(Step step, StepSource.Entry source)
	{
		return step.getData().getArea() == null ? Set.of() : actsOf(step, source, false).stream()
			.filter(sub -> sub.getKind() == Kind.ACT && !sub.getNpcIds().isEmpty()).map(SubStep::getNpcIds).findFirst().orElse(Set.of());
	}

	/**
	 * In core's cryptics naming both an NPC and an object, the NPC holds the key the object needs, except Juna's "speak to
	 * the guardian".
	 */
	public static boolean isKeyFromNpc(Step step)
	{
		boolean talk = step.getText() != null && step.getText().toLowerCase().contains("speak");
		return ClueTypes.CRYPTIC.equals(step.getType()) && !talk && !step.getNpcs().isEmpty()
			&& (!ObjectNames.core(step.getKey()).isEmpty() || !step.getObjectIds().isEmpty());
	}

	/** The prayer a Sherlock task turns on ("activate the chivalry prayer."), or null. */
	public static Prayer prayerToActivate(Step step)
	{
		Matcher task = !ClueTypes.SKILL.equals(step.getType()) || step.getText() == null ? null
			: ACTIVATE_PRAYER.matcher(step.getText().toLowerCase());
		if (task == null || !task.matches())
		{
			return null;
		}
		return Arrays.stream(Prayer.values()).filter(prayer -> prayer.name().equalsIgnoreCase(task.group(1))).findFirst().orElse(null);
	}

	/** What is done before the task, as a sub-step: done once an item is held, or once the items are gone. */
	public static SubStep beforeAct(StepSource.Before before)
	{
		SubStep.SubStepBuilder act = SubStep.of(Kind.ACT, before.getText()).npcIds(Set.copyOf(before.getNpcs()))
			.objectIds(Set.copyOf(before.getObjects())).option(before.getOption()).groundItem(before.getGroundItem());
		return before.getGone().isEmpty()
			? act.actItems(Set.copyOf(before.getItems())).tab(before.getItems().isEmpty() ? Tab.NONE : Tab.INVENTORY)
				.heldWhenDone(Set.copyOf(before.getHeld())).done(SubStep.Done.ITEM_HELD).build()
			: act.actItems(Set.copyOf(before.getGone())).done(SubStep.Done.ITEMS_GONE).build();
	}

	/** Core's name for the step's NPC as {@link Step#NPC_NAME}, which {@link Acts} names again at play. */
	/** A search of an object the clue doesn't name, by the cache's name for it, with its objects and Search option to colour. */
	private static SubStep searchNamed(Step step, SubStep act)
	{
		return !SEARCH_IT.equals(act.getText()) ? act : act.toBuilder().text(ObjectNames.search(step.getObjectIds()))
			.objectIds(Set.copyOf(step.getObjectIds())).option(StepHighlights.SEARCH_OPTION).build();
	}

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

	/** The step's one act: its marker's act, a key search, or what its type does. */
	static SubStep act(Step step, StepSource.Entry source)
	{
		if (step.getWaypointAct() != null)
		{
			return SubStep.of(Kind.ACT, step.getWaypointAct())
				.waypoint(step.isActAnywhere() ? null : step.getTargets().get(0))
				.actItems(step.getActItems()).build();
		}
		List<String> objectNames = ObjectNames.core(step.getKey());
		if (isKeyFromNpc(step))
		{
			String what = hasAny(objectNames) ? "the " + objectNames.get(0).toLowerCase() : "it";
			return SubStep.of(Kind.ACT, "Search " + what).build();
		}
		SubStep.SubStepBuilder act = SubStep.of(Kind.ACT, actText(step));
		String npc = talkedTo(step);
		String option = npc == null ? null : source.getNpcOption();
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
		List<String> objectNames = ObjectNames.core(step.getKey());
		if (hasAny(objectNames))
		{
			return "Search the " + objectNames.get(0).toLowerCase();
		}
		if (hasAny(step.getObjectIds()))
		{
			return SEARCH_IT;
		}
		return "Do the clue step";
	}

	static List<SubStep> actsOf(Step step, StepSource.Entry source, boolean takeFromStash)
	{
		SubStep main = act(step, source);
		if (step.getWaypointAct() != null)
		{
			return List.of(main);
		}
		String type = step.getType() == null ? "" : step.getType();
		switch (type)
		{
			case ClueTypes.EMOTE:
				return emote(step, source, takeFromStash);
			case ClueTypes.MUSIC:
				return music(step, source);
			case ClueTypes.FALO:
				return List.of(useOnFalo(List.of()).option(USE_OPTION).npcIds(targetNpcs(step)).build());
			case ClueTypes.SKILL:
				return List.of(handIn(step, List.of()));
			default:
				break;
		}
		List<StepIndex.Target> targets = step.getData().getTargets();
		int kill = 0;
		while (kill < targets.size() && targets.get(kill).getKind() != StepIndex.Target.Kind.KILL)
		{
			kill++;
		}
		if (kill < targets.size())
		{
			String howTo = source.getTargets().get(kill).getText();
			String text = source.getAct() != null ? source.getAct() : howTo != null ? howTo.replaceAll("\\.$", "") : "Kill the monster";
			List<SubStep> acts = source.getBefore().stream().map(ActParts::beforeAct).collect(toList());
			acts.add(SubStep.of(Kind.ACT, text).option(ATTACK_OPTION).npcIds(Set.copyOf(targets.get(kill).getNpcs())).build());
			return List.copyOf(acts);
		}
		if (isDig(step))
		{
			return List.of(main.toBuilder().text("Dig").detail(withFloor(step, source, source.getNote())).fights(fights(source))
				.again("Dig again").build());
		}
		if (source.getChallenge() != null && !step.getNpcs().isEmpty())
		{
			return talkThenChallenge(step, source);
		}
		return List.of(main.toBuilder().detail(withFloor(step, source, main.getDetail())).build());
	}

	private static List<SubStep> emote(Step step, StepSource.Entry source, boolean takeFromStash)
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
		List<String> stepEmotes = EMOTES.getOrDefault(step.getKey(), List.of());
		String emotes = stepEmotes.isEmpty() ? "the emote" : String.join(", then ", stepEmotes);
		acts.add(SubStep.of(Kind.ACT, "Perform " + emotes)
			.done(SubStep.Done.URI_HERE).fights(fights(source)).again("Perform " + emotes + " again").tab(StepGuide.Tab.EMOTES).build());
		acts.add(SubStep.of(Kind.ACT, "Talk to Uri")
			.option(TALK_OPTION).npcIds(URI_IDS).build());
		return List.copyOf(acts);
	}

	private static Map<String, List<String>> emotesByKey()
	{
		try
		{
			ClueScrollPlugin plugin = CoreClues.plugin();
			return CoreClues.steps().values().stream().filter(EmoteClue.class::isInstance).map(EmoteClue.class::cast)
				.collect(toUnmodifiableMap(clue -> CoreClueReader.facts(clue, plugin).getKey(), ActParts::emotesOf));
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("can't read core's emote clues", e);
		}
	}

	private static List<String> emotesOf(EmoteClue clue)
	{
		return Stream.of(clue.getFirstEmote(), clue.getSecondEmote()).filter(Objects::nonNull).map(Emote::getName)
			.collect(toUnmodifiableList());
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

	/** The track goes on first, on the way there; Cecilia ends the step only while it plays. */
	private static List<SubStep> music(Step step, StepSource.Entry source)
	{
		StepSource.Music music = source.getMusic();
		String track = music != null && music.getTrack() != null ? music.getTrack() : step.getText();
		return List.of(playTrack(track), talkTo(act("Talk to Cecilia", null).toBuilder(), "Cecilia").build());
	}

	/** Played in the music tab; done once it is the track playing. */
	private static SubStep playTrack(String track)
	{
		return SubStep.of(Kind.ACT, "Play " + track).track(track).done(SubStep.Done.TRACK_PLAYING).tab(StepGuide.Tab.MUSIC).build();
	}

	private static SubStep.SubStepBuilder useOnFalo(List<String> handInItems)
	{
		return SubStep.of(Kind.ACT, "Use " + (handInItems.isEmpty() ? "the item" : String.join(" and ", handInItems)) + " on Falo the Bard");
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

	private static List<SubStep> talkThenChallenge(Step step, StepSource.Entry source)
	{
		StepSource.Challenge challenge = source.getChallenge();
		String npc = step.getNpcs().get(0);
		SubStep talk = talkTo(SubStep.of(Kind.ACT, "Talk to " + npc).detail(withFloor(step, source, null))
			.done(SubStep.Done.CHALLENGE_ITEM), npc).build();
		if (challenge.getBox() != null)
		{
			String box = challenge.getBox().toLowerCase().contains("light") ? "puzzle box or light box" : "puzzle box";
			return List.of(talk, act("Open the " + box, null));
		}
		return List.of(talk, talkTo(act("Talk to " + npc + " again: answer the challenge", null).toBuilder(), npc).build());
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

	private static boolean fights(StepSource.Entry source)
	{
		return !source.getCombat().isEmpty();
	}

	/**
	 * Core's tile can be on another floor than the NPC (Donovan upstairs, the cache's tile on floor 1): hand data says
	 * so, else the cache's plane does.
	 */
	private static String withFloor(Step step, StepSource.Entry source, String detail)
	{
		String floor = source.getFloor();
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
