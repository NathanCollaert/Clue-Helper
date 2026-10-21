package com.cluehelper.guide;

import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Value;

/**
 * What to outline in menus for the current sub-step (B16): the right-click options on the item or object it uses, and
 * the line in the teleport interface that opens. Built by {@link StepGuide}; read by the overlay at draw time.
 */
@Value
@Builder(toBuilder = true)
public class MenuTarget
{
	public static final MenuTarget NONE = MenuTarget.builder().build();

	public enum Line
	{
		/** A 187 list, a chatbox option list or a right-click sub-menu: matched by label. */
		LABEL,
		/** Portal nexus: struct id in the list view; region shield, then the icon in the map view. */
		NEXUS,
		/** Jewellery box: script 1688's line index. */
		BOX,
		/** Fairy ring travel log entry by code, then the dials' confirm button. */
		FAIRY_CODE,
		/** Grouping: the minigame in the dropdown, then the Teleport button. */
		MINIGAME,
		/** A quetzal map (Renu's or the whistle's): the site's icon, placed by the cache's Quetzal table (B17). */
		QUETZAL
	}

	/** Right-click options on these items (inventory or worn). */
	@Builder.Default
	Set<Integer> itemIds = Set.of();
	/** Their names: a worn item's options carry no item id, only its name as the target. */
	@Builder.Default
	Set<String> itemNames = Set.of();
	/** Right-click options on these objects (base ids), and outlined in the scene. */
	@Builder.Default
	Set<Integer> objectIds = Set.of();
	/** Right-click options on these NPCs (Renu), and outlined in the scene. */
	@Builder.Default
	Set<Integer> npcIds = Set.of();
	/** Option labels from the top menu down a sub-menu; the last one names the destination. */
	@Builder.Default
	List<String> path = List.of();
	@Builder.Default
	Line line = Line.LABEL;
	/** NEXUS: struct id; BOX: line index; QUETZAL: the site's Quetzal table id. */
	int lineId;
	/** NEXUS: the map region shield; FAIRY_CODE: the code. */
	String lineKey;
	/** NEXUS: the cache's name of the destination, as the map icon shows it before its alias; MINIGAME: the minigame. */
	String lineName;
	/**
	 * The same landing through another house menu (Nathan, B16: he opened the jewellery box when the route named the
	 * glory): their interface lines are coloured too.
	 */
	@Builder.Default
	List<MenuTarget> alternatives = List.of();

	public boolean isEmpty()
	{
		return itemIds.isEmpty() && objectIds.isEmpty() && npcIds.isEmpty() && path.isEmpty() && line == Line.LABEL;
	}

	/** The destination label: the last option of the path. */
	public String destination()
	{
		return path.isEmpty() ? null : path.get(path.size() - 1);
	}
}
