package com.cluehelper.transport;

import com.cluehelper.player.Book;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** One way to change spellbook ({@code spellbooks.tsv}, PLAN §4 Data), with B04's measured costs. */
@Value
@Builder(toBuilder = true)
public class SpellbookChange
{
	public static final int NO_ID = -1;

	String name;
	/** A Spellbook Swap lasts one cast; everything else stays changed. */
	boolean temporary;
	/** Null when it works anywhere or inside the house. */
	WorldPoint tile;
	/** POH furniture: usable at the house node, once the house scan has seen it (M2). */
	boolean inHouse;
	@Builder.Default
	int objectId = NO_ID;
	@Builder.Default
	int npcId = NO_ID;
	/** Null when it works from any book. */
	Book from;
	/** Null when it goes to any book the player can use; else it toggles this book (to Standard when already on it). */
	Book toggles;
	int duration;
	int menuTicks;
	@Builder.Default
	List<Spend> spends = List.of();
	Requirements requirements;

	public boolean isAnywhere()
	{
		return tile == null && !inHouse;
	}
}
