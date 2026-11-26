package com.cluehelper.guide;

import com.cluehelper.Tables;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.*;
import com.cluehelper.route.Exit;
import com.cluehelper.step.*;
import com.cluehelper.transport.*;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import static java.util.stream.Collectors.*;
import com.cluehelper.guide.StepGuide.Tab;

/**
 * What the overlays mark for a sub-step: its items, tab, spell, menu target, and the one-click fill of the items,
 * objects and NPCs whose left click does what the step needs. Client thread only.
 */
@Singleton
final class StepHighlights
{
	private static final String TAKE_OPTION = "Take";
	static final String SEARCH_OPTION = "Search";
	private static final String REMOVE_OPTION = "Remove";
	static final String OPEN_OPTION = "Open";
	private static final String USE_OPTION = "Use";
	static final String BANK_OPTION = "Bank";
	private static final String CAST_OPTION = "Cast";
	private static final String CLOSE_OPTION = "Close";
	private static final Set<Integer> FAIRY_RING_OBJECTS = Tables.idSet("fairy ring objects");
	/** The house's spiritual fairy tree lists its ring options above "Tree", so a left click never opens the tree list. */
	private static final Set<Integer> NOT_FILLED_UNHOVERED = Set.of(ObjectID.POH_SPIRIT_RING, ObjectID.LEAGUE_5_POH_SPIRIT_RING,
		ObjectID.XMAS20_POH_SPIRIT_RING);

	@Inject
	Client client;
	@Inject
	PlayerModel player;
	@Inject
	BankMemory bankMemory;
	@Inject
	LeftClicks leftClicks;
	@Inject
	RowTargets rowTargets;
	@Inject
	TaskActs taskActs;
	@Inject
	DepositReminder deposit;
	@Inject
	ObeliskDestination obelisk;

	Highlights of(SubStep current, Step step)
	{
		if (current == null)
		{
			return Highlights.NONE;
		}
		Highlights.HighlightsBuilder shown = Highlights.builder().objectOption(current.getOption()).bankItems(current.getBankItems().stream()
			.map(id -> bankMemory.onlyInBankedKit(id) ? ItemID.HUNTSMANS_KIT : id).collect(toUnmodifiableSet()));
		switch (current.getKind())
		{
			case ACT:
				return actHighlights(shown, current, step);
			case EXIT:
				Exit exit = current.getExit();
				MenuTarget way = thenClicks(wayTarget(exit.getObjectId(), exit.getOption()),
					exit.getChat().isEmpty() ? List.of() : List.of(exit.getChat()));
				Set<Integer> forms = new HashSet<>(way.getObjectIds());
				forms.addAll(exit.getOtherForms());
				return shown.menuTarget(withDialogue(way.toBuilder().objectIds(Set.copyOf(forms)).build(), wayInOf(step, exit))).build();
			case WALK:
				// Gear that only works at the way in goes on there first: its walk leads to the spot, not the object
				if (current.getWayIn() != null && !current.getWayIn().isGearThere())
				{
					return wayInHighlights(shown, current, step);
				}
				break;
			case ENTER:
				return wayInHighlights(shown, current, step);
			case SPELLBOOK_SWAP:
				swapHighlights(shown, current.getBook());
				break;
			case TELEPORT:
				teleportHighlights(shown, current);
				break;
			case FAIRY_RING:
				if (current.getRow().getCode() != null)
				{
					shown.menuTarget(MenuTarget.fairyCode(current.getRow().getCode()).toBuilder().objectIds(FAIRY_RING_OBJECTS).build());
				}
				break;
			case CHANGE_BOOK:
				changeBookHighlights(shown, current);
				break;
			default:
				break;
		}
		return withOneClick(shown.build(), current);
	}

	/** The items that change as they are carried or put on, refreshed every tick. */
	Highlights refreshed(Highlights shown, SubStep current, Step step)
	{
		if (usesItemOnNpc(current, step))
		{
			return useOnNpc(shown.toBuilder(), current, step);
		}
		return withNamedItems(taskActs.withItems(shown, current, step), current);
	}

	/** An act's items are filled by what it asks for (equip on a left click, its own items), not by a teleport's left click. */
	Highlights withItemFill(Highlights shown, SubStep current)
	{
		if (current != null && current.getKind() == SubStep.Kind.ACT)
		{
			return shown;
		}
		return shown.toBuilder().fillItems(oneClickItems(shown, current, shown.getOneClickLabel())).build();
	}

	/** The STASH unit the act searches, or {@link StepRequirements#NO_STASH}. */
	int stashOf(SubStep act, Step step)
	{
		if (act.getDone() == SubStep.Done.STASH_DEPOSITED)
		{
			return deposit.getStashId();
		}
		boolean taking = act.getDone() == SubStep.Done.STASH_WITHDRAWN && step != null;
		return taking ? step.getRequirements().getStashObjectId() : StepRequirements.NO_STASH;
	}

	List<TileObject> fillObjects(List<TileObject> objects, Highlights shown)
	{
		String objectOption = shown.getObjectOption();
		// Filled until a hover shows otherwise: most left clicks do the step, and a hover is kept for later sessions
		if (objectOption != null)
		{
			return objects.stream().filter(object -> isFilledUnhovered(object) || leftClicks.isLeftClick(object, objectOption))
				.collect(toUnmodifiableList());
		}
		MenuTarget menuTarget = shown.getMenuTarget();
		boolean ring = menuTarget.getLine() == MenuLine.FAIRY_CODE && menuTarget.getLineKey() != null;
		String firstClick = menuTarget.getPath().isEmpty() ? null : menuTarget.getPath().get(0);
		String label = shown.getOneClickLabel();
		boolean houseList = menuTarget.getLine() == MenuLine.NEXUS || menuTarget.getLine() == MenuLine.BOX;
		return objects.stream()
			.filter(object -> isFilledUnhovered(object) || (ring ? ringGoesTo(leftClicks.object(object), menuTarget.getLineKey())
				: oneClickTo(leftClicks.object(object), label, firstClick) || isOpenedAlready(object, firstClick)
				|| houseList && MenuLabels.isOpener(leftClicks.object(object))))
			.collect(toUnmodifiableList());
	}

	/** Someone opened it already, so its left click now goes on through it ("Go-down" on an open trapdoor), unless it shuts it. */
	private boolean isOpenedAlready(TileObject object, String firstClick)
	{
		return OPEN_OPTION.equalsIgnoreCase(firstClick) && !leftClicks.offers(object, OPEN_OPTION)
			&& !CLOSE_OPTION.equalsIgnoreCase(leftClicks.object(object));
	}

	private boolean isFilledUnhovered(TileObject object)
	{
		return !leftClicks.isLearned(object) && !NOT_FILLED_UNHOVERED.contains(object.getId());
	}

	/**
	 * The act's items whose left click does the act: put on, taken off (a Max cape left-clicking "Home" isn't), the act's
	 * own option (Drop), else all.
	 */
	private Set<Integer> actItemFill(SubStep act)
	{
		if (act.getActOption() != null)
		{
			return act.getActItems().stream().filter(id -> act.getActOption().equalsIgnoreCase(leftClicks.item(id))).collect(toUnmodifiableSet());
		}
		if (takesOff(act))
		{
			return act.getActItems().stream().filter(leftClicks::removes).collect(toUnmodifiableSet());
		}
		return act.getDone() == SubStep.Done.WORN ? act.getActItems().stream().filter(leftClicks::wears).collect(toUnmodifiableSet())
			: act.getActItems();
	}

	private boolean takesOff(SubStep act)
	{
		return act.getDone() == SubStep.Done.SLOTS_EMPTY || takesGearOff(act);
	}

	/** Booths and chests whose left click opens the bank: "Bank", or "Use" on a chest. */
	List<TileObject> fillBanks(List<TileObject> banks)
	{
		return banks.stream().filter(bank -> leftClicks.isLeftClick(bank, BANK_OPTION) || leftClicks.isLeftClick(bank, USE_OPTION))
			.collect(toUnmodifiableList());
	}

	/** Bankers whose left click is "Bank": "Talk-to" until a swap puts it first. */
	List<NPC> fillBankers(List<NPC> bankers)
	{
		return bankers.stream().filter(banker -> BANK_OPTION.equalsIgnoreCase(leftClicks.npc(banker))).collect(toUnmodifiableList());
	}

	List<NPC> fillNpcs(List<NPC> npcs, String npcOption)
	{
		return npcOption == null ? List.of()
			: npcs.stream().filter(npc -> !leftClicks.isLearned(npc) || npcOption.equalsIgnoreCase(leftClicks.npc(npc)))
				.collect(toUnmodifiableList());
	}

	/** An act's own items, its STASH unit or the gear to put on; else only its one-click fill. */
	private Highlights actHighlights(Highlights.HighlightsBuilder shown, SubStep current, Step step)
	{
		shown.tab(current.getTab());
		if (current.getGroundItem() >= 0)
		{
			return shown.menuTarget(MenuTarget.builder().itemIds(Set.of(current.getGroundItem())).path(List.of(TAKE_OPTION)).build()).build();
		}
		int stash = stashOf(current, step);
		if (stash != StepRequirements.NO_STASH)
		{
			return shown.menuTarget(MenuTarget.builder().objectIds(Set.of(stash)).build()).objectOption(SEARCH_OPTION).build();
		}
		if (!current.getActItems().isEmpty() && !waitsForDepositBox(current) && isAtWaypoint(current))
		{
			boolean takesOff = takesOff(current);
			// Deposited items are clicked in the deposit box, which shows the inventory and worn items itself
			boolean deposits = current.getDone() == SubStep.Done.ITEMS_GONE;
			shown.items(current.getActItems()).fillItems(actItemFill(current))
				.tab(takesOff ? Tab.EQUIPMENT : deposits ? Tab.NONE : Tab.INVENTORY);
			if (takesOff || current.getActOption() != null)
			{
				return shown.menuTarget(rowTargets.items(current.getActItems(), takesOff ? REMOVE_OPTION : current.getActOption())).build();
			}
			return (current.getDone() == SubStep.Done.WORN ? shown.menuTarget(wearTargets(current.getActItems())) : shown).build();
		}
		if (usesItemOnNpc(current, step))
		{
			return useOnNpc(shown, current, step);
		}
		if (current.getStage() != null && current.getOption() != null && !current.getStage().getDialogue().isEmpty())
		{
			shown.menuTarget(withStageDialogue(current));
		}
		return withOneClick(shown.build(), current);
	}

	/** An act at a marked tile (a feel, a drop) uses its item only once the player stands on that tile. */
	private boolean isAtWaypoint(SubStep act)
	{
		return act.getKind() != SubStep.Kind.ACT || act.getWaypoint() == null || act.getWaypoint().equals(player.getPosition().getTile());
	}

	/** A gear act whose pieces are all worn takes them off: they count against its bonus. */
	private boolean takesGearOff(SubStep current)
	{
		return current.getDone() == SubStep.Done.WORN && player.wornIds().containsAll(current.getActItems());
	}

	/** Items to deposit stay unmarked until the deposit box is open, so they don't read as items to put on. */
	private boolean waitsForDepositBox(SubStep current)
	{
		return current.getDone() == SubStep.Done.ITEMS_GONE && !current.getObjectIds().isEmpty()
			&& !Widgets.shown(client, InterfaceID.BankDepositbox.UNIVERSE);
	}

	/**
	 * The way in's object with its option and dialogue, its closed form opened first; once its items are picked up, the
	 * object they are used on.
	 */
	private static MenuTarget enterTarget(SubStep current)
	{
		StepIndex.WayIn way = current.getWayIn();
		StepIndex.PickUp pickUp = way.getPickUp();
		boolean pickedUp = pickUp != null && current.getText().startsWith(StepGuide.KEEP_GOING);
		if (pickedUp)
		{
			return MenuTarget.builder().objectIds(Set.copyOf(pickUp.getObjects())).path(List.of(pickUp.getOption())).build();
		}
		MenuTarget target = wayTarget(way.getObject(), way.getOption());
		if (way.getClosed() >= 0)
		{
			target = target.toBuilder().objectIds(Set.of(way.getObject(), way.getClosed())).path(List.of(OPEN_OPTION, way.getOption())).build();
		}
		return withDialogue(target, way);
	}

	/**
	 * The way in's object to click, or the item to use on it: its own use items, or a carried item that stands in for
	 * gear nothing worn meets (a talisman on the altar ruins, which a tiara or Runecraft cape opens with a left click).
	 */
	private Highlights wayInHighlights(Highlights.HighlightsBuilder shown, SubStep current, Step step)
	{
		StepIndex.WayIn way = current.getWayIn();
		if (!way.getUse().isEmpty())
		{
			return useOnWayIn(shown, way);
		}
		if (way.getObject() < 0 && !current.getObjectIds().isEmpty())
		{
			return shown.menuTarget(wayTarget(current.getObjectIds(), current.getOption())).build();
		}
		Set<Integer> instead = carriedInsteadOfWorn(step);
		return instead.isEmpty() ? shown.menuTarget(enterTarget(current)).build()
			: useOn(shown, MenuTarget.builder().objectIds(Set.of(way.getObject())), instead, Set.of());
	}

	private Set<Integer> carriedInsteadOfWorn(Step step)
	{
		if (step == null || step.getData() == null)
		{
			return Set.of();
		}
		Set<Integer> worn = player.wornIds();
		Set<Integer> inventory = player.inventoryIds();
		return step.getData().getRequires().stream()
			.filter(need -> need.isWear() && need.getIds().stream().noneMatch(worn::contains))
			.flatMap(need -> inventory.stream().filter(id -> need.isCarriedInstead(Set.of(id))))
			.collect(toUnmodifiableSet());
	}

	/** The carried item to use until one is picked, then the way's object it is used on. */
	private Highlights useOnWayIn(Highlights.HighlightsBuilder shown, StepIndex.WayIn way)
	{
		Set<Integer> items = player.inventoryIds().stream().filter(way.getUse()::contains).collect(toUnmodifiableSet());
		shown.usedItem(items.stream().findFirst().orElse(-1));
		return useOn(shown, MenuTarget.builder().objectIds(Set.of(way.getObject())), items, Set.of());
	}

	/** The carried item the step names until one is picked, then the NPC it is used on (Falo the Bard). */
	private Highlights useOnNpc(Highlights.HighlightsBuilder shown, SubStep current, Step step)
	{
		Set<Integer> items = player.inventoryIds().stream().filter(id -> isStepItem(step, id)).collect(toUnmodifiableSet());
		Set<Integer> worn = player.wornIds().stream().filter(id -> isStepItem(step, id)).collect(toUnmodifiableSet());
		if (!client.isWidgetSelected() && items.isEmpty() && !worn.isEmpty())
		{
			// A worn item can't be used on him: off first (Falo's gloves)
			return shown.items(worn).fillItems(worn.stream().filter(leftClicks::removes).collect(toUnmodifiableSet()))
				.menuTarget(rowTargets.items(worn, REMOVE_OPTION)).tab(Tab.EQUIPMENT).build();
		}
		Set<Integer> useOnLeftClick = items.stream().filter(id -> USE_OPTION.equals(leftClicks.item(id)))
			.collect(toUnmodifiableSet());
		return useOn(shown, MenuTarget.builder().npcIds(current.getNpcIds()), items, useOnLeftClick);
	}

	/** {@code items} to use until one is picked, then {@code usedOn}. */
	private Highlights useOn(Highlights.HighlightsBuilder shown, MenuTarget.MenuTargetBuilder usedOn, Set<Integer> items, Set<Integer> fill)
	{
		List<String> use = List.of(USE_OPTION);
		if (client.isWidgetSelected())
		{
			return shown.menuTarget(usedOn.path(use).build()).build();
		}
		return shown.items(items).fillItems(fill).menuTarget(MenuTarget.builder().itemIds(items).path(use).build()).tab(Tab.INVENTORY).build();
	}

	private static boolean usesItemOnNpc(SubStep current, Step step)
	{
		return current != null && step != null && current.getKind() == SubStep.Kind.ACT && Acts.USE_OPTION.equals(current.getOption())
			&& !current.getNpcIds().isEmpty();
	}

	static boolean isStepItem(Step step, int itemId)
	{
		return step.getRequirements().getItems().stream().anyMatch(item -> item.fulfilledBy(itemId));
	}

	/** The act's click, then its chat options in turn. */
	private static MenuTarget withStageDialogue(SubStep current)
	{
		MenuTarget act = MenuTarget.builder().npcIds(current.getNpcIds()).objectIds(current.getObjectIds())
			.path(List.of(current.getOption())).build();
		return thenClicks(act, current.getStage().getDialogue());
	}

	/** The step's hand-written way in that this exit row is, or null. */
	private static StepIndex.WayIn wayInOf(Step step, Exit exit)
	{
		StepIndex.WayIn way = step == null ? null : step.getData().getEntry();
		return SubSteps.isWayIn(exit, way) ? way : null;
	}

	/** A conversation's chat options follow its option, so each menu's pick is coloured in turn. */
	private static MenuTarget withDialogue(MenuTarget target, StepIndex.WayIn way)
	{
		return way == null ? target : thenClicks(target, way.getDialogue());
	}

	/** The target's path with {@code clicks} after it, each coloured in its menu in turn. */
	private static MenuTarget thenClicks(MenuTarget target, List<String> clicks)
	{
		if (clicks.isEmpty())
		{
			return target;
		}
		List<String> path = new ArrayList<>(target.getPath());
		path.addAll(clicks);
		return target.toBuilder().path(List.copyOf(path)).build();
	}

	/** Shortest Path names objects and NPCs alike by id, so the id is looked for as both. */
	private static MenuTarget wayTarget(int id, String option)
	{
		return wayTarget(id < 0 ? Set.of() : Set.of(id), option);
	}

	private static MenuTarget wayTarget(Set<Integer> ids, String option)
	{
		return MenuTarget.builder().objectIds(ids).npcIds(ids).path(option == null || option.isEmpty() ? List.of() : List.of(option)).build();
	}

	/** A spell, the Minigame Teleport spell, house furniture or a spirit tree, a named item, or the held item with its menu. */
	private void teleportHighlights(Highlights.HighlightsBuilder shown, SubStep current)
	{
		CatalogRow row = current.getRow();
		if (isSpell(row))
		{
			int spell = row.getSpellWidget();
			shown.spellWidget(spell).tab(Tab.MAGIC);
			if (row.getLine() == MenuLine.BOAT)
			{
				shown.menuTarget(RowTargets.line(row).build());
			}
			else
			{
				spellOption(shown, row, spell);
			}
		}
		else if (row.getCategory() == Category.MINIGAME)
		{
			shown.spellWidget(rowTargets.minigameSpellWidget(player.currentBook())).tab(Tab.MAGIC)
				.menuTarget(RowTargets.line(row).path(row.getOptions()).build());
		}
		else if (row.isLocated())
		{
			shown.menuTarget(obelisk.nextClick(rowTargets.locatedTeleport(current)));
		}
		else if (current.getActItemNamed() != null)
		{
			shown.tab(Tab.INVENTORY);
		}
		else
		{
			Set<Integer> held = withHeld(shown, rowTargets.heldUses(row));
			ScrollBook.Scroll scroll = ScrollBook.scroll(row);
			shown.scroll(scroll).scrollLeftClick(row.getOptions().size() == 1)
				.menuTarget(scroll != null && !isBookDefault(held, row) ? rowTargets.items(held, OPEN_OPTION) : rowTargets.heldTeleport(current, held));
		}
	}

	/**
	 * The book's first option is its default teleport, named after that place or plainly "Teleport": either way it is no
	 * list opener, so any other scroll is reached through Open.
	 */
	private boolean isBookDefault(Set<Integer> book, CatalogRow row)
	{
		String label = row.getOptions().get(row.getOptions().size() - 1);
		return book.stream().anyMatch(id -> teleportsTo(leftClicks.item(id), label));
	}

	/**
	 * A spell with a second landing (Camelot Teleport's Seers') casts one of them on a left click, the one Configure set:
	 * the other is the spell's right-click option, so the spell is only outlined and the option's menu line marked.
	 */
	private void spellOption(Highlights.HighlightsBuilder shown, CatalogRow row, int spell)
	{
		List<String> choice = row.getOptions();
		String leftClick = leftClicks.spell(spell);
		String option = choice.isEmpty() ? CAST_OPTION : choice.get(choice.size() - 1);
		if (!MenuLabels.matches(leftClick, option))
		{
			shown.menuTarget(MenuTarget.builder().widgetIds(Set.of(spell)).path(List.of(option)).build()).spellRightClick(true);
		}
	}

	/** Spellbook Swap's right-click menu names each book it can swap to ("Arceuus"), so the step's book is marked there. */
	private static void swapHighlights(Highlights.HighlightsBuilder shown, Book book)
	{
		int spell = InterfaceID.MagicSpellbook.SPELLBOOK_SWAP;
		shown.spellWidget(spell).tab(Tab.MAGIC).spellRightClick(true)
			.menuTarget(MenuTarget.builder().widgetIds(Set.of(spell)).path(List.of(book.label())).build());
	}

	/** An item that changes the book anywhere (a cape), or house furniture that does. */
	private void changeBookHighlights(Highlights.HighlightsBuilder shown, SubStep current)
	{
		if (current.getChange().isAnywhere())
		{
			Set<Integer> held = withHeld(shown, rowTargets.heldUses(current.getChange()));
			shown.menuTarget(rowTargets.heldBookChange(held, current.getBook()));
		}
		else if (current.getChange().isInHouse())
		{
			shown.menuTarget(rowTargets.houseBookChange(current.getChange(), current.getBook()));
		}
	}

	private static boolean isSpell(CatalogRow row)
	{
		return row.getCategory() == Category.SPELL || row.getCategory() == Category.HOME;
	}

	/**
	 * The held items go on {@code shown}, outlined in the tab they are in.
	 *
	 * @return the items' ids
	 */
	private static Set<Integer> withHeld(Highlights.HighlightsBuilder shown, List<ItemUse> held)
	{
		Set<Integer> items = held.stream().map(ItemUse::getItemId).collect(toUnmodifiableSet());
		boolean worn = !held.isEmpty() && held.stream().allMatch(use -> use.getContainer() == Container.WORN);
		shown.heldUses(held).items(items)
			.tab(held.isEmpty() ? Tab.NONE : worn ? Tab.EQUIPMENT : Tab.INVENTORY);
		return items;
	}

	/** The one-click fill: the items whose left click goes where the menu target does. */
	private Highlights withOneClick(Highlights built, SubStep current)
	{
		String label = directLabel(built.getMenuTarget(), current);
		return built.toBuilder().oneClickLabel(label).fillItems(oneClickItems(built, current, label)).build();
	}

	/** None for a quetzal: its option only opens the map. */
	private String directLabel(MenuTarget menuTarget, SubStep current)
	{
		if (menuTarget.getLine() == MenuLine.QUETZAL)
		{
			return null;
		}
		if (menuTarget.destination() != null)
		{
			return menuTarget.destination();
		}
		List<String> path = current.getRow() == null ? List.of() : current.getRow().getOptions();
		return path.isEmpty() ? null : path.get(path.size() - 1);
	}

	private Set<Integer> oneClickItems(Highlights shown, SubStep current, String label)
	{
		boolean signals = shown.getMenuTarget().getLine() == MenuLine.QUETZAL;
		boolean teleport = current != null && current.getKind() == SubStep.Kind.TELEPORT && current.getRow() != null;
		boolean oneDestination = teleport && (label == null ? current.getRow().getOptions().isEmpty() : current.getRow().isSingleLanding());
		return shown.getHeldUses().stream()
			.filter(use -> signals ? RowTargets.SIGNAL_OPTION.equalsIgnoreCase(leftClicks.item(use))
				: oneDestination ? isOwnTeleport(leftClicks.item(use)) : teleportsTo(leftClicks.swappedOrLearned(use), label))
			.map(ItemUse::getItemId)
			.collect(toUnmodifiableSet());
	}

	/** Each item's own option coloured in its menu: Wield on the axe, Wear on the boots. */
	private MenuTarget wearTargets(Set<Integer> items)
	{
		List<MenuTarget> each = items.stream().filter(player.inventoryIds()::contains).filter(id -> leftClicks.wearOption(id) != null).sorted().map(id -> rowTargets.items(Set.of(id), leftClicks.wearOption(id)))
			.collect(toList());
		return each.isEmpty() ? MenuTarget.NONE : each.get(0).toBuilder().alternatives(List.copyOf(each.subList(1, each.size()))).build();
	}

	/** The carried item the act names, refreshed every tick as it arrives (the puzzle box, after the talk). */
	private Highlights withNamedItems(Highlights shown, SubStep current)
	{
		boolean clicksItem = current != null && (current.getKind() == SubStep.Kind.ACT || current.getKind() == SubStep.Kind.TELEPORT);
		if (!clicksItem || current.getActItemNamed() == null)
		{
			return shown;
		}
		Set<Integer> named = player.inventoryIds().stream()
			.filter(id -> client.getItemDefinition(id).getName().toLowerCase().startsWith(current.getActItemNamed()))
			.collect(toUnmodifiableSet());
		String option = current.getActOption();
		Highlights.HighlightsBuilder next = shown.toBuilder().items(named)
			.fillItems(option == null ? named : named.stream().filter(id -> option.equalsIgnoreCase(leftClicks.item(id))).collect(toUnmodifiableSet()))
			.tab(named.isEmpty() ? shown.getTab() : Tab.INVENTORY);
		return option == null ? next.build() : takeOutFromBook(next, named, current, option);
	}

	/** The book's entry for the scroll, outlined once the book is open, and its Remove option coloured. */
	private Highlights takeOutFromBook(Highlights.HighlightsBuilder shown, Set<Integer> items, SubStep current, String option)
	{
		boolean fromBook = current.getDone() == SubStep.Done.ITEM_CARRIED && current.getRow() != null;
		ScrollBook.Scroll scroll = fromBook ? ScrollBook.scroll(current.getRow()) : null;
		boolean bookOpen = scroll != null && Widgets.shown(client, InterfaceID.Bookofscrolls.CONTENTS);
		return shown.scroll(scroll)
			.menuTarget(bookOpen
				? MenuTarget.builder().widgetIds(scroll.widgets()).path(List.of(ScrollBook.REMOVE_OPTION)).build()
				: rowTargets.items(items, option))
			.build();
	}

	private static boolean oneClickTo(String leftClick, String label, String firstClick)
	{
		return teleportsTo(leftClick, label) || firstClick != null && MenuLabels.matches(leftClick, firstClick);
	}

	/** Menu Entry Swapper's "Last-destination" carries no code, so the remembered last ring stands in for it. */
	private boolean ringGoesTo(String leftClick, String code)
	{
		if (MenuLabels.isRingDestination(leftClick, code))
		{
			return true;
		}
		PlayerSnapshot snapshot = player.getSnapshot();
		String last = snapshot == null ? null : snapshot.getLastFairyRing();
		return leftClick != null && !leftClick.contains("(") && MenuLabels.isLastDestination(leftClick + " (" + last + ")", code);
	}

	/** Left clicks of teleport items that don't teleport. */
	private static final Set<String> NOT_TELEPORTS = Set.copyOf(Tables.keys("not teleports"));

	/**
	 * A list opener's click only opens the list, unless the row ends on that very option:
	 * then nothing follows it.
	 */
	private static boolean teleportsTo(String option, String destination)
	{
		if (option == null || destination == null)
		{
			return false;
		}
		boolean rowsLastClick = MenuLabels.normalise(option).equals(MenuLabels.normalise(destination));
		return rowsLastClick || !MenuLabels.isOpener(option) && MenuLabels.matches(option, destination);
	}

	/**
	 * An item with one destination names no option in its row (its left click may be "Invoke" or "Teleport"), so
	 * any left click that is an action of its own is its teleport: with one destination there is no list to open.
	 */
	private static boolean isOwnTeleport(String option)
	{
		return option != null && !NOT_TELEPORTS.contains(MenuLabels.normalise(option));
	}
}
