package com.cluehelper.guide;

import com.cluehelper.player.Book;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.SpellbookChange;
import java.util.Set;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

@Value
@Builder
public class SubStep
{
	public enum Kind
	{
		WALK,
		/** Core's own markers guide this walk. */
		WALK_TO_CLUE,
		WILDERNESS_EXIT,
		TELEPORT,
		SPELLBOOK_SWAP,
		FAIRY_RING,
		CHANGE_BOOK,
		BANK,
		WITHDRAW,
		/** Talk, search, dig or emote. */
		ACT
	}

	Kind kind;
	String text;
	String detail;
	/** Null on core's target and for travel that lands far away. */
	WorldPoint waypoint;
	/** Null when the sub-step completes some other way than arriving within {@link #radius}. */
	WorldPoint goal;
	int radius;
	Book book;
	/** The teleport to highlight, or a fairy ring's landing. */
	CatalogRow row;
	SpellbookChange change;
	@Builder.Default
	Set<Integer> bankItems = Set.of();
	/** An act's item, used by its left click: the strange device for a feel. */
	@Builder.Default
	Set<Integer> actItems = Set.of();

	public boolean isTravel()
	{
		return kind == Kind.TELEPORT || kind == Kind.FAIRY_RING;
	}
}
