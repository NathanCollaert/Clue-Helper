package com.cluehelper.step;

import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Value;
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
		/** Get the item Charlie asks for; done once it is carried. */
		FETCH,
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
	/** The key or the item Charlie wants: the stage is done once it is carried. Null for other stages. */
	ItemRequirement item;
	/** What gathering the item takes (a rod and bait), needed while the stage is the source's. */
	@Builder.Default
	List<ItemRequirement> tools = List.of();
	/** Tiles that count only while the named patch is grown (a Farming Guild redwood), by tile. */
	@Builder.Default
	Map<WorldPoint, String> grownOnly = Map.of();
	/** The step's own items are used here (Sherlock's task), so they come before this stage, not at the hand-in. */
	boolean takesStepItems;
	/** Needs the act wants worn, by name (Ghommal's +100 melee strength): put on just before. */
	@Builder.Default
	List<String> worn = List.of();
	/** Objects the stage is done on (an anvil): outlined, and the walk ends beside them. */
	@Builder.Default
	Set<Integer> objectIds = Set.of();
	/** NPCs the stage is done with (the Grand Exchange clerks): outlined, and the walk ends on them. */
	@Builder.Default
	Set<Integer> npcIds = Set.of();
}
