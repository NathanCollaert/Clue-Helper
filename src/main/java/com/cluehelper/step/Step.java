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
	List<CacheRow> cacheRows;
	/** False when the step data lacks this key; the step still has core's targets. */
	boolean known;
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
}
