package com.cluehelper.guide;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;

/** The objects and NPCs found in the loaded scene for the current sub-step, searched again every few ticks. */
@Value
@Builder(toBuilder = true)
class SceneHighlights
{
	static final SceneHighlights NONE = SceneHighlights.builder().build();

	@Builder.Default
	List<TileObject> objects = List.of();
	@Builder.Default
	List<NPC> npcs = List.of();
	/** Outlined objects whose left click does what the step needs. */
	@Builder.Default
	List<TileObject> fillObjects = List.of();
	@Builder.Default
	List<NPC> fillNpcs = List.of();
	/** The option an outlined NPC is filled for when it is its left click (the Guardian mummy's Start-minigame). */
	String npcOption;
	/** The tick of the last search; {@link ActWatcher#NEVER} forces one. */
	@Builder.Default
	int foundTick = ActWatcher.NEVER;
	/**
	 * Searches since the last that found something: a house's furniture appears a tick or two after the load, so empty
	 * searches repeat every tick at first, then every few ticks.
	 */
	@Builder.Default
	int emptySearches = 0;

	boolean isEmpty()
	{
		return objects.isEmpty() && npcs.isEmpty();
	}
}
