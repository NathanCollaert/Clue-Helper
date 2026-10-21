package com.cluehelper.transport;

import com.cluehelper.player.ItemUse;
import java.util.List;
import lombok.Value;

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
	/** Empty when unavailable. */
	List<ItemUse> witness;
	List<String> whyNot;
	/** The epoch minute a failing cooldown clears, or {@link Long#MAX_VALUE}. */
	long passesFrom;
}
