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
	/** Interface components whose options count, by widget id (a scroll book's entry). */
	@Builder.Default
	Set<Integer> widgetIds = Set.of();
	@Builder.Default
	List<String> path = List.of();
	@Builder.Default
	Line line = Line.LABEL;
	/** Set by the line factories below: a struct id, a box line index or a Quetzal table id. */
	int lineId;
	/** Set by the line factories below: a map region shield or a fairy ring code. */
	String lineKey;
	/** Set by the line factories below: the cache's name of a nexus destination, or a minigame. */
	String lineName;
	/** The same landing through another house menu (the jewellery box for the glory), so its lines are coloured too. */
	@Builder.Default
	List<MenuTarget> alternatives = List.of();

	/** A nexus line: its struct id, the map region shield, and the cache's name of the destination. */
	public static MenuTarget nexus(int structId, String region, String placeName)
	{
		return builder().line(Line.NEXUS).lineId(structId).lineKey(region).lineName(placeName).build();
	}

	/** A jewellery box line by its index. */
	public static MenuTarget box(int lineIndex)
	{
		return builder().line(Line.BOX).lineId(lineIndex).build();
	}

	/** The fairy ring dials on a code. */
	public static MenuTarget fairyCode(String code)
	{
		return builder().line(Line.FAIRY_CODE).lineKey(code).build();
	}

	/** A minigame's card by its name. */
	public static MenuTarget minigame(String name)
	{
		return builder().line(Line.MINIGAME).lineName(name).build();
	}

	/** A quetzal map site by its Quetzal table id. */
	public static MenuTarget quetzal(int tableId)
	{
		return builder().line(Line.QUETZAL).lineId(tableId).build();
	}

	public boolean isEmpty()
	{
		return itemIds.isEmpty() && objectIds.isEmpty() && npcIds.isEmpty() && path.isEmpty() && line == Line.LABEL;
	}

	public String destination()
	{
		return path.isEmpty() ? null : path.get(path.size() - 1);
	}
}
