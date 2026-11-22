package com.cluehelper.transport;

import com.cluehelper.player.ItemUse;
import java.util.List;
import lombok.Value;

@Value
public class RowAvailability
{
	CatalogRow row;
	boolean available;
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
