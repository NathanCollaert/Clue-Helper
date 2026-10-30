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
	/** Of the why-nots, the item clauses not met, each as its ways round (25 ecto-tokens, or an unlock). */
	List<List<String>> missingItems;
	/** Of the why-nots, the levels short: "70 Agility (has 50)". */
	List<String> missingLevels;
	/** Of the why-nots, the quests not finished, by name. */
	List<String> missingQuests;
	/** The why-nots that are no item, level or quest: a members world, a house's furniture, a var not read. */
	List<String> otherReasons;
	/** The epoch minute a failing cooldown clears, or {@link Long#MAX_VALUE}. */
	long passesFrom;
}
