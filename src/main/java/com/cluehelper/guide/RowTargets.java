package com.cluehelper.guide;

import com.cluehelper.player.*;
import com.cluehelper.transport.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;

/** What to click for a catalog row: its object or NPC and menu path, its held items, or its spell's button. Client thread only. */
@Slf4j
@Singleton
final class RowTargets
{
	static final String SIGNAL_OPTION = "Signal";

	private static final String TRAVEL_OPTION = "Travel";
	private static final String TREE_OPTION = "Tree";
	private static final String SPELLBOOK_OPTION = "Spellbook";
	/** Between a menu cell's option and the list line it opens. */
	static final String MENU_STEP = " > ";
	private static final int PARAM_SPELL_WIDGET = 596;
	/** Catalog landings spread 0–2 tiles, so another menu's line within this lands at the same place. */
	private static final int SAME_LANDING_TILES = 3;
	/** The patch objects are multilocs on their transmit varbits; the grown tree is the form they show. */
	private static final Set<Integer> PLANTED_SPIRIT_TREES = Set.of(ObjectID.FARMING_SPIRIT_TREE_PATCH_1,
		ObjectID.FARMING_SPIRIT_TREE_PATCH_2, ObjectID.FARMING_SPIRIT_TREE_PATCH_3, ObjectID.FARMING_SPIRIT_TREE_PATCH_4,
		ObjectID.FARMING_SPIRIT_TREE_PATCH_5, ObjectID.SPIRIT_TREE_FULLYGROWN);
	/** Minigame Teleport is a spell on every book, so it is cast rather than picked from the Grouping tab. */
	private static final Map<Book, Integer> MINIGAME_SPELLS = Map.of(
		Book.STANDARD, InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_STANDARD,
		Book.ANCIENT, InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_ANCIENT,
		Book.LUNAR, InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_LUNAR,
		Book.ARCEUUS, InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_ARCEUUS);

	@Inject
	private Client client;
	@Inject
	private PlayerModel player;
	@Inject
	private AvailabilityService availability;
	private final Map<String, Integer> spellWidgets = new HashMap<>();

	/** The row's object or NPC, with the menu its catalog line names. */
	MenuTarget located(CatalogRow row)
	{
		Set<Integer> objects = objectsOf(row);
		MenuTarget.MenuTargetBuilder target = MenuTarget.builder().objectIds(objects);
		String menu = row.getMenu();
		if (menu == null)
		{
			return target.build();
		}
		String[] parts = menu.split(" ", 2);
		boolean outside = !row.isInHouse();
		switch (parts[0])
		{
			case "quetzal":
				return MenuTarget.quetzal(row.quetzalTableId()).toBuilder().objectIds(objects)
					.npcIds(Set.of(NpcID.QUETZAL_CHILD_GREEN)).path(List.of(TRAVEL_OPTION)).build();
			case "list":
				return target.path(listPath(row, outside, parts[1])).build();
			case "nexus":
			{
				String[] nexus = parts[1].split(" ", 2);
				return MenuTarget.nexus(Integer.parseInt(nexus[0]), nexus[1], row.nexusPlace()).toBuilder()
					.objectIds(objects).build();
			}
			case "box":
				// The ornate box's own option repeats the last place it sent the player to: one click when it's this one.
				return MenuTarget.box(Integer.parseInt(parts[1])).toBuilder().objectIds(objects)
					.path(List.of(row.destination())).build();
			case "npc":
				return MenuTarget.builder().npcIds(Set.of(row.getObjectId())).path(List.of(parts[1].split(MENU_STEP))).build();
			default:
				return target.path(List.of(parts[1].split(MENU_STEP))).build();
		}
	}

	/** The teleport's object, with the house furniture that lands at the same place as alternatives. */
	MenuTarget locatedTeleport(SubStep teleport)
	{
		return located(teleport.getRow()).toBuilder().alternatives(sameLanding(teleport)).build();
	}

	/** The held items with the teleport's option, or Signal for a quetzal; house furniture landing there as alternatives. */
	MenuTarget heldTeleport(SubStep teleport, Set<Integer> held)
	{
		CatalogRow row = teleport.getRow();
		List<String> path = optionPath(row);
		MenuTarget.MenuTargetBuilder target = row.isQuetzalMap()
			? MenuTarget.quetzal(row.quetzalTableId()).toBuilder().path(List.of(SIGNAL_OPTION))
			: MenuTarget.builder().path(path.isEmpty() ? wornOption(held) : path);
		return withItems(target, held).alternatives(sameLanding(teleport)).build();
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

	MenuTarget plantedTree(Set<Integer> treeIds, String line)
	{
		return MenuTarget.builder().objectIds(treeIds).path(List.of(TRAVEL_OPTION, TREE_OPTION, line)).build();
	}

	MenuTarget items(Set<Integer> itemIds, String option)
	{
		return withItems(MenuTarget.builder(), itemIds).path(List.of(option)).build();
	}

	/** The options after the row name's item: "Ardougne cloak: Kandarin Monastery" is Kandarin Monastery. */
	List<String> optionPath(CatalogRow row)
	{
		String[] parts = row.getName().split(": ");
		return parts.length < 2 ? List.of() : List.of(parts).subList(1, parts.length);
	}

	boolean hasOneDestination(SubStep current)
	{
		return current != null && current.getKind() == SubStep.Kind.TELEPORT && current.getRow() != null
			&& optionPath(current.getRow()).isEmpty();
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
			.collect(Collectors.toUnmodifiableList());
	}

	List<ItemUse> heldUses(SpellbookChange change)
	{
		return heldUses(CatalogRow.builder().name(change.getName()).requirements(change.getRequirements()).build());
	}

	/** -1 when none is known. */
	int spellWidget(CatalogRow row)
	{
		RuneModel runes = player.getRuneModel();
		if (runes == null)
		{
			return -1;
		}
		return spellWidgets.computeIfAbsent(row.getName(), name ->
		{
			String spellName = spellName(name);
			Book book = row.getRequirements().getBook();
			for (Spell spell : runes.getSpells())
			{
				if (spell.getName().equalsIgnoreCase(spellName) && (book == null || spell.getBook() == book))
				{
					int widget = client.getItemDefinition(spell.getObj()).getIntValue(PARAM_SPELL_WIDGET);
					return widget > 0 ? widget : -1;
				}
			}
			log.debug("no spell widget for {}", name);
			return -1;
		});
	}

	int minigameSpellWidget(Book book)
	{
		return MINIGAME_SPELLS.getOrDefault(book, InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_STANDARD);
	}

	private MenuTarget.MenuTargetBuilder withItems(MenuTarget.MenuTargetBuilder target, Set<Integer> itemIds)
	{
		Set<String> names = itemIds.stream().map(id -> MenuLabels.normalise(client.getItemDefinition(id).getName()))
			.collect(Collectors.toUnmodifiableSet());
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
		List<MenuTarget> same = new ArrayList<>();
		for (RowAvailability judged : availability.getCarried().available())
		{
			CatalogRow row = judged.getRow();
			boolean house = row.isInHouse() && row.getMenu() != null;
			if (house && row != current.getRow() && row.getDestination() != null
				&& PositionRules.near(row.getDestination(), landing, SAME_LANDING_TILES))
			{
				same.add(located(row));
			}
		}
		return List.copyOf(same);
	}

	/** On the house's spiritual fairy tree "Ring-configure" opens a list too, so the Tree option is named. */
	private static List<String> listPath(CatalogRow row, boolean outside, String line)
	{
		if (outside)
		{
			return List.of(TRAVEL_OPTION, line);
		}
		return row.getCategory() == Category.SPIRIT_TREE ? List.of(TREE_OPTION, line) : List.of(line);
	}

	private static Set<Integer> objectsOf(CatalogRow row)
	{
		HouseNeed need = row.isInHouse() ? row.getRequirements().getHouse() : null;
		if (need != null)
		{
			return Set.copyOf(need.getKind() == HouseNeed.Kind.NEXUS ? HouseState.NEXUS_OBJECTS : need.getIds());
		}
		if (row.getObjectId() == ObjectID.SPIRIT_TREE_FULLYGROWN)
		{
			return PLANTED_SPIRIT_TREES;
		}
		return row.getObjectId() < 0 ? Set.of() : Set.of(row.getObjectId());
	}

	private static String spellName(String rowName)
	{
		String name = rowName;
		int colon = name.indexOf(':');
		if (colon >= 0)
		{
			name = name.substring(0, colon);
		}
		int bracket = name.indexOf(" (");
		return bracket >= 0 ? name.substring(0, bracket) : name;
	}
}
