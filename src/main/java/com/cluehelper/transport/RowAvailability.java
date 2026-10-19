package com.cluehelper.transport;

import com.cluehelper.player.ItemUse;
import java.util.List;
import lombok.Value;

/** One catalog row judged against one snapshot. */
@Value
public class RowAvailability
{
	public enum Confidence
	{
		SURE,
		/** Available, but an item it spends charges of has no known count. A tier, not a why-not. */
		CHARGES_UNKNOWN,
		/** Something it depends on hasn't been read yet (quests before the first tick, an unwatched var). */
		UNREAD
	}

	CatalogRow row;
	boolean available;
	Confidence confidence;
	/** The held items that satisfy it, per container; empty when unavailable. */
	List<ItemUse> witness;
	/** Why it can't be used: "needs 2 law runes (has 1)", "Fairytale II - Cure a Queen not finished". */
	List<String> whyNot;
	/** The epoch minute a failing cooldown clears, or {@link Long#MAX_VALUE}. */
	long passesFrom;
}
