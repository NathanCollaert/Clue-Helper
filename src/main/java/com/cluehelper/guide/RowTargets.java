package com.cluehelper.guide;

import com.cluehelper.Tables;
import com.cluehelper.player.*;
import com.cluehelper.transport.*;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import static java.util.stream.Collectors.*;

/** What to click for a catalog row: its object or NPC and menu path, its held items, or its spell's button. Client thread only. */
@Singleton
final class RowTargets
{
	static final String SIGNAL_OPTION = "Signal";

	private static final String SPELLBOOK_OPTION = "Spellbook";
	private static final int PARAM_SPELL_WIDGET = 596;
	/** Catalog landings spread 0–2 tiles, so another menu's line within this lands at the same place. */
	private static final int SAME_LANDING_TILES = 3;
	/** Minigame Teleport is a spell on every book, so it is cast rather than picked from the Grouping tab. */
	private static final Map<Book, Integer> MINIGAME_SPELLS = Tables.byKey("minigame spells", Book::valueOf, row -> row.id(0));

	@Inject
	Client client;
	@Inject
	PlayerModel player;
	@Inject
	AvailabilityService availability;

	/** The row's object or NPC, with the menu its catalog line names. */
	MenuTarget located(CatalogRow row)
	{
		return line(row).objectIds(row.getObjects()).npcIds(row.getNpcs()).path(row.getClicks()).build();
	}

	/** The menu line the row picks, by its kind and what identifies it. */
	static MenuTarget.MenuTargetBuilder line(CatalogRow row)
	{
		return MenuTarget.builder().line(row.getLine()).lineId(row.getLineId()).lineKey(row.getLineKey()).lineName(row.getLineName())
			.icon(row.getIcon());
	}

	/** The teleport's object, with the house furniture that lands at the same place as alternatives. */
	MenuTarget locatedTeleport(SubStep teleport)
	{
		return located(teleport.getRow()).toBuilder().alternatives(sameLanding(teleport)).build();
	}

	/**
	 * The held items with the teleport's option, or Signal for a quetzal; a boat teleport's list row; house furniture
	 * landing there as alternatives.
	 */
	MenuTarget heldTeleport(SubStep teleport, Set<Integer> held)
	{
		CatalogRow row = teleport.getRow();
		List<String> path = row.getLine() == MenuLine.QUETZAL ? List.of(SIGNAL_OPTION)
			: row.getOptions().isEmpty() ? wornOption(held) : row.getOptions();
		return withItems(line(row).path(path), held).alternatives(sameLanding(teleport)).build();
	}

	/**
	 * A one-place teleport has no menu line: in the inventory its left click teleports, but worn it is the item's
	 * first worn option (Invoke on a skull sceptre). Empty when none of the items is worn.
	 */
	private List<String> wornOption(Set<Integer> held)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		for (int itemId : held)
		{
			String option = snapshot != null && snapshot.getWorn().containsKey(itemId)
				? client.getItemDefinition(itemId).getStringValue(ParamID.OC_ITEM_OP1) : null;
			if (option != null && !option.isEmpty())
			{
				return List.of(option);
			}
		}
		return List.of();
	}

	MenuTarget heldBookChange(Set<Integer> held, Book book)
	{
		return withItems(MenuTarget.builder(), held).path(List.of(SPELLBOOK_OPTION, book.label())).build();
	}

	MenuTarget houseBookChange(SpellbookChange change, Book book)
	{
		return MenuTarget.builder().objectIds(Set.of(change.getObjectId())).path(List.of(SPELLBOOK_OPTION, book.label())).build();
	}

	MenuTarget items(Set<Integer> itemIds, String option)
	{
		return withItems(MenuTarget.builder(), itemIds).path(List.of(option)).build();
	}

	/** The row's carried items; runes are left out, as the spell widget covers those. */
	List<ItemUse> heldUses(CatalogRow row)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		RuneModel runes = player.getRuneModel();
		if (snapshot == null || runes == null)
		{
			return List.of();
		}
		RowAvailability judged = new AvailabilityEngine(runes, id -> "").judge(row, snapshot, AvailabilityService.nowMinutes());
		return judged.getWitness().stream()
			.filter(use -> use.getContainer().isCarried())
			.filter(use -> !runes.isRune(use.getItemId()))
			.collect(toUnmodifiableList());
	}

	List<ItemUse> heldUses(SpellbookChange change)
	{
		return heldUses(CatalogRow.builder().name(change.getName()).requirements(change.getRequirements()).build());
	}

	/** -1 when none is known. */
	int spellWidget(CatalogRow row)
	{
		int widget = row.getSpellObj() < 0 ? -1 : client.getItemDefinition(row.getSpellObj()).getIntValue(PARAM_SPELL_WIDGET);
		return widget > 0 ? widget : -1;
	}

	int minigameSpellWidget(Book book)
	{
		return MINIGAME_SPELLS.getOrDefault(book, InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_STANDARD);
	}

	private MenuTarget.MenuTargetBuilder withItems(MenuTarget.MenuTargetBuilder target, Set<Integer> itemIds)
	{
		Set<String> names = itemIds.stream().map(id -> MenuLabels.normalise(client.getItemDefinition(id).getName()))
			.collect(toUnmodifiableSet());
		return target.itemIds(itemIds).itemNames(names);
	}

	private List<MenuTarget> sameLanding(SubStep current)
	{
		WorldPoint landing = current.getGoal();
		PlayerSnapshot snapshot = player.getSnapshot();
		if (landing == null || snapshot == null)
		{
			return List.of();
		}
		return availability.getCarried().available().stream().map(RowAvailability::getRow)
			.filter(row -> row.isInHouse() && hasMenu(row) && row != current.getRow() && row.getDestination() != null
				&& PositionRules.near(row.getDestination(), landing, SAME_LANDING_TILES))
			.map(this::located).collect(toUnmodifiableList());
	}

	/** Furniture with no option or menu line to pick is used by its left click: the portal chamber's portals. */
	private static boolean hasMenu(CatalogRow row)
	{
		return row.getLine() != MenuLine.LABEL || !row.getClicks().isEmpty();
	}
}
