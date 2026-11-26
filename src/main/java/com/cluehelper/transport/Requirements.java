package com.cluehelper.transport;

import com.cluehelper.player.Book;
import java.util.*;
import lombok.*;
import net.runelite.api.Quest;

@Value
@Builder(toBuilder = true)
public class Requirements
{
	public static final int NO_WILDERNESS_LIMIT = Integer.MAX_VALUE;

	@Builder.Default
	List<ItemClause> items = List.of();
	@Builder.Default
	List<LevelNeed> levels = List.of();
	@Builder.Default
	Set<Quest> quests = Set.of();
	@Builder.Default
	List<VarCheck> vars = List.of();
	@Builder.Default
	int maxWildernessLevel = NO_WILDERNESS_LIMIT;
	boolean members;
	/** Null for rows that work on every book. Availability never filters by it. */
	Book book;
	/** An item with no charges left doesn't pay for a row that spends them. */
	boolean spendsCharges;
	/** Null for rows outside the house. */
	HouseNeed house;
	/** Patch names; each must be known grown. */
	@Builder.Default
	Set<String> plantedTrees = Set.of();
}
