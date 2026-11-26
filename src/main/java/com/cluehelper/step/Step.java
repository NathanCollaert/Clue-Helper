package com.cluehelper.step;

import java.util.*;
import lombok.*;
import net.runelite.api.coords.WorldPoint;

@Value
@Builder(toBuilder = true)
public class Step
{
	/** Stands for the step's NPC in baked act text: core names it at play, by quest state or from the client. */
	public static final String NPC_NAME = "npcname";

	String key;
	String type;
	/** Beginner to master, or "unknown" for a key the step data doesn't have. */
	String tier;
	String text;
	TargetKind kind;
	/** The cache tile for coordinate clues, never core's mirror twin. */
	@Builder.Default
	List<WorldPoint> targets = List.of();
	@Builder.Default
	List<String> npcs = List.of();
	/** Where an AREA step's NPC roams, or where a skill challenge's objects are. */
	@Builder.Default
	List<Integer> regions = List.of();
	@Builder.Default
	List<Integer> objectIds = List.of();
	@Builder.Default
	List<String> objectNames = List.of();
	@Builder.Default
	StepRequirements requirements = StepRequirements.none();
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

	/**
	 * In core's cryptics naming both an NPC and an object, the NPC holds the key the object needs, except Juna's "speak to
	 * the guardian".
	 */
	public boolean isKeyFromNpc()
	{
		boolean talk = text != null && text.toLowerCase().contains("speak");
		return ClueTypes.CRYPTIC.equals(type) && !talk && !npcs.isEmpty() && (!objectNames.isEmpty() || !objectIds.isEmpty());
	}

	/** The text with the step's NPC named; "the NPC" for a step core names none for. */
	public String namingNpc(String text)
	{
		return text == null ? null : text.replace(NPC_NAME, npcs.isEmpty() ? "the NPC" : npcs.get(0));
	}

	/** The NPCs the step is done with: none when its NPC only drops the key for its object. */
	public List<String> getNpcsAtStep()
	{
		return isKeyFromNpc() ? List.of() : npcs;
	}
}
