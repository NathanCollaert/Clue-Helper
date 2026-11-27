package com.cluehelper.guide;

import com.cluehelper.transport.MenuLine;
import java.util.*;
import lombok.*;

@Value
@Builder(toBuilder = true)
public class MenuTarget
{
	public static final MenuTarget NONE = MenuTarget.builder().build();

	@Builder.Default
	Set<Integer> itemIds = Set.of();
	/** A worn item's options carry no item id, only its name as the target. */
	@Builder.Default
	Set<String> itemNames = Set.of();
	@Builder.Default
	Set<Integer> objectIds = Set.of();
	@Builder.Default
	Set<Integer> npcIds = Set.of();
	/** NPCs by name as {@link MenuLabels#normalise} gives it: the step's own NPC, whose ids core knows and we don't. */
	@Builder.Default
	Set<String> npcNames = Set.of();
	/** Interface components whose options count, by widget id (a scroll book's entry). */
	@Builder.Default
	Set<Integer> widgetIds = Set.of();
	@Builder.Default
	List<String> path = List.of();
	@Builder.Default
	MenuLine line = MenuLine.LABEL;
	/** A nexus line's struct id or a jewellery box line's index. */
	int lineId;
	/** A nexus line's map region shield or a fairy ring code. */
	String lineKey;
	/** The cache's name of a nexus destination, a minigame or a boat's dock; a chained fairy ring code's whole chain. */
	String lineName;
	/** Where a Quetzal site's icon sits on the map, x and y. */
	int[] icon;
	/** The same landing through another house menu (the jewellery box for the glory), so its lines are coloured too. */
	@Builder.Default
	List<MenuTarget> alternatives = List.of();

	/** The fairy ring dials on a code. */
	public static MenuTarget fairyCode(String code)
	{
		return builder().line(MenuLine.FAIRY_CODE).lineKey(code).build();
	}

	public boolean isEmpty()
	{
		return itemIds.isEmpty() && objectIds.isEmpty() && npcIds.isEmpty() && path.isEmpty() && line == MenuLine.LABEL;
	}

	public String destination()
	{
		return path.isEmpty() ? null : path.get(path.size() - 1);
	}
}
