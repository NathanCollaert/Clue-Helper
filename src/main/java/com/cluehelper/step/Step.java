package com.cluehelper.step;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** One clue step, resolved from core's clue (PLAN §4 item 2). Immutable. */
@Value
@Builder
public class Step
{
	String key;
	String type;
	/** beginner to master, or "unknown" for a key the step data doesn't have. */
	String tier;
	String text;
	TargetKind kind;
	/** Normalised top-level tiles: the cache tile for coordinate clues, never core's mirror twin. */
	List<WorldPoint> targets;
	List<String> npcs;
	/** Where an AREA step's NPC roams, or where a skill challenge's objects are. */
	List<Integer> regions;
	List<Integer> objectIds;
	List<String> objectNames;
	StepRequirements requirements;
	List<CacheRow> cacheRows;
	/** Whether the step data has this key; an unknown step still has core's targets. */
	boolean known;
}
