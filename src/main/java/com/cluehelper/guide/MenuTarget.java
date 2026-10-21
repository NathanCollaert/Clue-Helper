package com.cluehelper.guide;

import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class MenuTarget
{
	public static final MenuTarget NONE = MenuTarget.builder().build();

	public enum Line
	{
		LABEL,
		NEXUS,
		BOX,
		FAIRY_CODE,
		MINIGAME,
		QUETZAL
	}

	@Builder.Default
	Set<Integer> itemIds = Set.of();
	/** A worn item's options carry no item id, only its name as the target. */
	@Builder.Default
	Set<String> itemNames = Set.of();
	@Builder.Default
	Set<Integer> objectIds = Set.of();
	@Builder.Default
	Set<Integer> npcIds = Set.of();
	@Builder.Default
	List<String> path = List.of();
	@Builder.Default
	Line line = Line.LABEL;
	/** NEXUS: struct id; BOX: jewellery box line index; QUETZAL: the site's Quetzal table id. */
	int lineId;
	/** NEXUS: the map region shield; FAIRY_CODE: the code. */
	String lineKey;
	/** NEXUS: the cache's name of the destination, as the map icon shows it before its alias; MINIGAME: the minigame. */
	String lineName;
	/** The same landing through another house menu (the jewellery box for the glory), so its lines are coloured too. */
	@Builder.Default
	List<MenuTarget> alternatives = List.of();

	public boolean isEmpty()
	{
		return itemIds.isEmpty() && objectIds.isEmpty() && npcIds.isEmpty() && path.isEmpty() && line == Line.LABEL;
	}

	public String destination()
	{
		return path.isEmpty() ? null : path.get(path.size() - 1);
	}
}
