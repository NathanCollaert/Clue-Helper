package com.cluehelper.guide;

import com.cluehelper.Tables;
import com.cluehelper.player.*;
import com.cluehelper.transport.*;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import static java.util.stream.Collectors.*;

/** What to click for a catalog row: its object or NPC and menu path, its held items, or its spell's button. Client thread only. */
@Singleton
final class RowTargets
{
	static final String SIGNAL_OPTION = "Signal";

	private static final String LAST_DESTINATION_OPTION = "Last-destination";
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

	/**
	 * The teleport's object or NPC, with the house furniture that lands at the same place as alternatives; Renu's
	 * Last-destination when the quetzal flies there.
	 */
	MenuTarget locatedTeleport(SubStep teleport)
	{
		CatalogRow row = teleport.getRow();
		MenuTarget.MenuTargetBuilder target = isLastQuetzalSite(row) ? lastDestination() : line(row).path(row.getClicks());
		return target.objectIds(row.getObjects()).npcIds(row.getNpcs()).alternatives(sameLanding(teleport)).build();
	}

	/**
	 * The held items with the teleport's option; for a quetzal, Last-destination when it flies there, else Signal and
	 * the site on the map; a boat teleport's list row; house furniture landing there as alternatives.
	 */
	MenuTarget heldTeleport(SubStep teleport, Set<Integer> held)
	{
		CatalogRow row = teleport.getRow();
		MenuTarget.MenuTargetBuilder target = isLastQuetzalSite(row) ? lastDestination()
			: row.getLine() == MenuLine.QUETZAL ? line(row).path(List.of(SIGNAL_OPTION))
			: line(row).path(row.getOptions().isEmpty() ? wornOption(held) : row.getOptions());
		return withItems(target, held).alternatives(sameLanding(teleport)).build();
	}

	/** The whistle and Renu share it, so it flies straight there with no map. */
	private static MenuTarget.MenuTargetBuilder lastDestination()
	{
		return MenuTarget.builder().path(List.of(LAST_DESTINATION_OPTION));
	}

	/** The game keeps the quetzals' last site by its id in the quetzal table, whose map icon is where the row's is. */
	private boolean isLastQuetzalSite(CatalogRow row)
	{
		if (row.getLine() != MenuLine.QUETZAL || row.getIcon() == null)
		{
			return false;
		}
		int lastId = client.getVarbitValue(VarbitID.QUETZAL_LAST_DESTINATION);
		List<Integer> sites = client.getDBRowsByValue(DBTableID.Quetzal.ID, DBTableID.Quetzal.COL_ID, 0, lastId);
		if (sites.isEmpty())
		{
			return false;
		}
		int site = sites.get(0);
		int[] icon = row.getIcon();
		return icon[0] == quetzalField(site, DBTableID.Quetzal.COL_IF_X_POS) && icon[1] == quetzalField(site, DBTableID.Quetzal.COL_IF_Y_POS);
	}

	private int quetzalField(int site, int column)
	{
		Object[] values = client.getDBTableField(site, column, 0);
		return values.length == 0 ? -1 : (int) values[0];
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
