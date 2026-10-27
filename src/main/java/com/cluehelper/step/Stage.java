package com.cluehelper.step;

import com.cluehelper.player.Book;
import java.util.*;
import lombok.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/** One place of a step done in several: travelled to in turn, each ending in an act of its own. */
@Value
@Builder(toBuilder = true)
public class Stage
{
	public enum Kind
	{
		/** Kill one of the NPCs for the key; done once the key is carried. */
		KEY,
		/** One cryptic of a three-step; core drops it once solved. */
		PART,
		/** A skill challenge's task; done once core marks the challenge completed. */
		TASK,
		/** The step's own target, done last. */
		FINAL
	}

	Kind kind;
	String act;
	String detail;
	/** Any one of these will do. */
	List<WorldPoint> tiles;
	/** Core marks only the step's own targets; for other stages the walk ends on our marker. */
	boolean ourMarker;
	/** An NPC that wanders: being this close to a tile is arriving. */
	int radius;
	/** The key: the stage is done once it is carried. Null for other stages. */
	ItemRequirement item;
	/** Any of these owned anywhere, the bank too, skips the stage: the way in's requires bring it from there. */
	@Builder.Default
	Set<Integer> skippedWhenOwned = Set.of();
	/** Tiles that count only while the named patch is grown (a Farming Guild redwood), by tile. */
	@Builder.Default
	Map<WorldPoint, String> grownOnly = Map.of();
	/** The objects of a place in the player's house (its spirit tree): the place counts only while the house has one. */
	@Builder.Default
	Set<Integer> houseObjects = Set.of();
	/** The step's own items are used here (Sherlock's task), so they come before this stage, not at the hand-in. */
	boolean takesStepItems;
	/** Needs the act wants worn, by name (Ghommal's +100 melee strength): put on just before. */
	@Builder.Default
	List<String> worn = List.of();
	/** Core's empty-slot requirements the act wants met (no gloves, weapon or shield for aerial fishing): taken off first. */
	@Builder.Default
	List<ItemRequirement> emptied = List.of();
	/** Places that span an area: outlined, and anywhere in one is arriving. */
	@Builder.Default
	List<StepIndex.Place> spans = List.of();
	/** Done at the place before the act (Alry's bird, king worms), in order. */
	@Builder.Default
	List<StepIndex.Before> before = List.of();
	/** Objects the stage is done on (an anvil): outlined, and the walk ends beside them. */
	@Builder.Default
	Set<Integer> objectIds = Set.of();
	/** NPCs the stage is done with (the Grand Exchange clerks): outlined, and the walk ends on them. */
	@Builder.Default
	Set<Integer> npcIds = Set.of();
	/** Per tile, core's items that place takes others in place of (the True Blood Altar takes pure essence). */
	@Builder.Default
	Map<WorldPoint, List<StepIndex.Alternative>> uses = Map.of();
	/** The spellbook the stage is done on, so a route changes book before arriving; null for any. */
	Book book;
	/** The menu option that does the task on them ("Chop down"); null when there is nothing to click. */
	String option;
}
