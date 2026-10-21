package com.cluehelper.transport;

import com.cluehelper.player.Book;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.Quest;

/**
 * What one catalog row needs, parsed from Shortest Path's columns. A cell that didn't parse leaves
 * an entry in {@link #getErrors()}: the row stays in the catalog and is never available, so it
 * can't silently disappear.
 */
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
	/** The highest Wilderness level the row can be used from. */
	@Builder.Default
	int maxWildernessLevel = NO_WILDERNESS_LIMIT;
	boolean members;
	/** The book a spell row belongs to; null for rows that work on every book. Availability never filters by it. */
	Book book;
	/** The row spends item charges (Shortest Path's Consumable = T), so unknown charges lower its confidence. */
	boolean spendsCharges;
	/** The house piece a POH row needs; null for rows outside the house (B16). */
	HouseNeed house;
	/** Planted spirit trees the row starts or lands at, by patch name ("Port Sarim"); each must be known grown (B17). */
	@Builder.Default
	Set<String> plantedTrees = Set.of();
	@Builder.Default
	List<String> errors = List.of();
}
