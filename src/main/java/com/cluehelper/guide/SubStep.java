package com.cluehelper.guide;

import com.cluehelper.player.Book;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.SpellbookChange;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** One thing to do next on the committed route (PLAN §5.5), with what completes it. */
@Value
@Builder
public class SubStep
{
	public enum Kind
	{
		/** Walk to a waypoint of ours: a fairy ring, an altar, a bank. */
		WALK,
		/** The last walk, onto core's target: core's own markers guide it. */
		WALK_TO_CLUE,
		WILDERNESS_EXIT,
		TELEPORT,
		/** Spellbook Swap to the book of the cast that follows. */
		SPELLBOOK_SWAP,
		FAIRY_RING,
		CHANGE_BOOK,
		BANK,
		/** Talk, search, dig or emote: panel text only in M1. */
		ACT
	}

	Kind kind;
	String text;
	/** A hint under the line ("right-click the cape"), or null. */
	String detail;
	/** Where our arrow and markers go; null on core's target and for travel that lands far away. */
	WorldPoint waypoint;
	/** Arriving or landing within {@link #radius} of it completes the sub-step; null when another check does. */
	WorldPoint goal;
	int radius;
	/** For a book change or a Swap: the book 4070 must reach. */
	Book book;
	/** The teleport to highlight (spell or item), or a fairy ring's landing. */
	CatalogRow row;
	SpellbookChange change;

	public boolean isTravel()
	{
		return kind == Kind.TELEPORT || kind == Kind.FAIRY_RING;
	}
}
