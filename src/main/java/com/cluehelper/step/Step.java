package com.cluehelper.step;

import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

@Value
@Builder(toBuilder = true)
public class Step
{
	String key;
	String type;
	/** Beginner to master, or "unknown" for a key the step data doesn't have. */
	String tier;
	String text;
	TargetKind kind;
	/** The cache tile for coordinate clues, never core's mirror twin. */
	List<WorldPoint> targets;
	List<String> npcs;
	/** Where an AREA step's NPC roams, or where a skill challenge's objects are. */
	List<Integer> regions;
	List<Integer> objectIds;
	List<String> objectNames;
	StepRequirements requirements;
	/**
	 * Set on a hot/cold step rewritten to one probe tile of our own: what to do there. The target is then our waypoint,
	 * not core's.
	 */
	String waypointAct;
	/** Items the act's left click uses, highlighted at our waypoint. */
	@Builder.Default
	Set<Integer> actItems = Set.of();
	/** The act may be done where the player stands: no marker for it. */
	boolean actAnywhere;
	/** Places visited in turn, each with its own act; empty for a step done in one place. */
	@Builder.Default
	List<Stage> stages = List.of();
	/** The step data's entry; {@link StepIndex.Entry#NONE} for a key the data lacks. */
	@Builder.Default
	StepIndex.Entry data = StepIndex.Entry.NONE;
	/** Tiles core doesn't give but the step is done at too (Brimstail inside his cave): arriving there is arriving. */
	@Builder.Default
	List<WorldPoint> otherTiles = List.of();
	/** An emote step's emotes in order, by core's names. */
	@Builder.Default
	List<String> emotes = List.of();

	/** False when the step data lacks this key; the step still has core's targets. */
	public boolean isKnown()
	{
		return data != StepIndex.Entry.NONE;
	}

	public List<CacheRow> getCacheRows()
	{
		return List.copyOf(data.getRows());
	}

	/**
	 * In core's cryptics naming both an NPC and an object, the NPC holds the key the object needs, except Juna's "speak to
	 * the guardian".
	 */
	public boolean isKeyFromNpc()
	{
		boolean talk = text != null && text.toLowerCase().contains("speak");
		return ClueTypes.CRYPTIC.equals(type) && !talk && hasAny(npcs) && (hasAny(objectNames) || hasAny(objectIds));
	}

	/** The NPCs the step is done with: none when its NPC only drops the key for its object. */
	public List<String> getNpcsAtStep()
	{
		return npcs == null || isKeyFromNpc() ? List.of() : npcs;
	}

	private static boolean hasAny(List<?> list)
	{
		return list != null && !list.isEmpty();
	}
}
