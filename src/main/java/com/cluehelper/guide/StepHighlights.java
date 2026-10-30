package com.cluehelper.guide;

import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.*;
import com.cluehelper.route.Exit;
import com.cluehelper.step.*;
import com.cluehelper.transport.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.widgets.Widget;

/**
 * What the overlays mark for a sub-step: its items, tab, spell, menu target, and the one-click fill of the items,
 * objects and NPCs whose left click does what the step needs. Client thread only.
 */
@Singleton
final class StepHighlights
{
	static final String SEARCH_OPTION = "Search";
	private static final String REMOVE_OPTION = "Remove";
	private static final String OPEN_OPTION = "Open";
	private static final String USE_OPTION = "Use";
	private static final Set<SubStep.Done> PUTS_ON = EnumSet.of(SubStep.Done.EQUIPPED, SubStep.Done.WAY_IN_WORN,
		SubStep.Done.GEAR_WORN, SubStep.Done.ITEM_WORN);
	private static final String CLOSE_OPTION = "Close";
	private static final String TELEPORT_TO_DESTINATION = "Teleport to Destination";
	private static final Set<Integer> FAIRY_RING_OBJECTS = Set.of(ObjectID.FAIRYRING_HOMEHUB, ObjectID.FAIRYRING_MINORHUB,
		ObjectID.POH_FAIRY_RING, ObjectID.POH_SPIRIT_RING);
	/** The house's spiritual fairy tree lists its ring options above "Tree", so a left click never opens the tree list. */
	private static final Set<Integer> NOT_FILLED_UNHOVERED = Set.of(ObjectID.POH_SPIRIT_RING);

	@Inject
	private Client client;
	@Inject
	private PlayerModel player;
	@Inject
	private BankMemory bankMemory;
	@Inject
	private LeftClicks leftClicks;
	@Inject
	private RowTargets rowTargets;
	@Inject
	private TaskActs taskActs;
	@Inject
	private DepositReminder deposit;
	@Inject
	private ObeliskDestination obelisk;

	Highlights of(SubStep current, Step step)
	{
		if (current == null)
		{
			return Highlights.NONE;
		}
		Highlights.HighlightsBuilder shown = Highlights.builder().objectOption(current.getOption()).bankItems(current.getBankItems().stream()
			.map(id -> bankMemory.onlyInBankedKit(id) ? ItemID.HUNTSMANS_KIT : id).collect(Collectors.toUnmodifiableSet()));
		switch (current.getKind())
		{
			case ACT:
				return actHighlights(shown, current, step);
			case EXIT:
				Exit exit = current.getExit();
				return shown.menuTarget(withDialogue(wayTarget(exit.getObjectId(), exit.getOption()), wayInOf(step, exit))).build();
			case ENTER:
				return current.getWayIn().getUse().isEmpty() ? shown.menuTarget(enterTarget(current)).build()
					: useOnWayIn(shown, current.getWayIn());
			case SPELLBOOK_SWAP:
				shown.spellWidget(InterfaceID.MagicSpellbook.SPELLBOOK_SWAP).tab(StepGuide.Tab.MAGIC);
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
		return withNamedItems(taskActs.withItems(withEquipItems(shown, current, step), current, step), current);
	}

	/** An act's items are filled by what it asks for (equip on a left click, its own items), not by a teleport's left click. */
	Highlights withItemFill(Highlights shown, SubStep current, Step step)
	{
		if (current != null && current.getKind() == SubStep.Kind.ACT)
		{
			return withEquipItems(shown, current, step);
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
		boolean taking = act.getDone() == SubStep.Done.STASH_WITHDRAWN && step != null && step.getRequirements() != null;
		return taking ? step.getRequirements().getStashObjectId() : StepRequirements.NO_STASH;
	}

	List<TileObject> fillObjects(List<TileObject> objects, Highlights shown)
	{
		String objectOption = shown.getObjectOption();
		// Filled until a hover shows otherwise: most left clicks do the step, and a hover is kept for later sessions
		if (objectOption != null)
		{
			return objects.stream().filter(object -> isFilledUnhovered(object) || leftClicks.isLeftClick(object, objectOption))
				.collect(Collectors.toUnmodifiableList());
		}
		MenuTarget menuTarget = shown.getMenuTarget();
		boolean ring = menuTarget.getLine() == MenuTarget.Line.FAIRY_CODE && menuTarget.getLineKey() != null;
		String firstClick = menuTarget.getPath().isEmpty() ? null : menuTarget.getPath().get(0);
		String label = shown.getOneClickLabel();
		boolean houseList = menuTarget.getLine() == MenuTarget.Line.NEXUS || menuTarget.getLine() == MenuTarget.Line.BOX;
		return objects.stream()
			.filter(object -> isFilledUnhovered(object) || (ring ? ringGoesTo(leftClicks.object(object), menuTarget.getLineKey())
				: oneClickTo(leftClicks.object(object), label, firstClick) || isOpenedAlready(object, firstClick)
				|| houseList && MenuLabels.isOpener(leftClicks.object(object))))
			.collect(Collectors.toUnmodifiableList());
	}

	/** Someone opened it already, so its left click now goes on through it ("Go-down" on Doris' trapdoor), unless it shuts it. */
	private boolean isOpenedAlready(TileObject object, String firstClick)
	{
		return OPEN_OPTION.equalsIgnoreCase(firstClick) && !leftClicks.offers(object, OPEN_OPTION)
			&& !CLOSE_OPTION.equalsIgnoreCase(leftClicks.object(object));
	}

	private boolean isFilledUnhovered(TileObject object)
	{
		return !leftClicks.isLearned(object) && !NOT_FILLED_UNHOVERED.contains(object.getId());
	}

	/** Deposited items are clicked in the deposit box, which shows the inventory and worn items itself. */
	private static StepGuide.Tab actItemsTab(SubStep.Done done)
	{
		switch (done)
		{
			case SLOTS_EMPTY:
				return StepGuide.Tab.EQUIPMENT;
			case ITEMS_GONE:
				return StepGuide.Tab.NONE;
			default:
				return StepGuide.Tab.INVENTORY;
		}
	}

	/** The act's items whose left click does the act: put on, taken off (a Max cape left-clicking "Home" isn't), else all. */
	private Set<Integer> actItemFill(SubStep act)
	{
		switch (act.getDone())
		{
			case WAY_IN_WORN:
			case GEAR_WORN:
			case ITEM_WORN:
				return act.getActItems().stream().filter(leftClicks::wears).collect(Collectors.toUnmodifiableSet());
			case SLOTS_EMPTY:
				return act.getActItems().stream().filter(leftClicks::removes).collect(Collectors.toUnmodifiableSet());
			default:
				return act.getActItems();
		}
	}

	List<NPC> fillNpcs(List<NPC> npcs, String npcOption)
	{
		return npcOption == null ? List.of()
			: npcs.stream().filter(npc -> !leftClicks.isLearned(npc) || npcOption.equalsIgnoreCase(leftClicks.npc(npc)))
				.collect(Collectors.toUnmodifiableList());
	}

	/** An act's own items, its STASH unit, the gear to put on, or a planted tree's line; else only its one-click fill. */
	private Highlights actHighlights(Highlights.HighlightsBuilder shown, SubStep current, Step step)
	{
		shown.tab(current.getTab());
		int stash = stashOf(current, step);
		if (stash != StepRequirements.NO_STASH)
		{
			return shown.menuTarget(MenuTarget.builder().objectIds(Set.of(stash)).build()).objectOption(SEARCH_OPTION).build();
		}
		if (current.getDone() == SubStep.Done.EQUIPPED)
		{
			return withEquipItems(shown.build(), current, step);
		}
		if (!current.getActItems().isEmpty())
		{
			boolean takesOff = current.getDone() == SubStep.Done.SLOTS_EMPTY;
			shown.items(current.getActItems()).fillItems(actItemFill(current)).tab(actItemsTab(current.getDone()));
			String putOn = PUTS_ON.contains(current.getDone()) ? wearOption(current.getActItems()) : null;
			String option = takesOff ? REMOVE_OPTION : putOn;
			return (option != null ? shown.menuTarget(rowTargets.items(current.getActItems(), option)) : shown).build();
		}
		String planted = taskActs.plantedTreeLine(current, step);
		if (planted != null)
		{
			shown.menuTarget(rowTargets.plantedTree(current.getObjectIds(), planted));
		}
		else if (current.getStage() != null && current.getOption() != null && !current.getStage().getDialogue().isEmpty())
		{
			shown.menuTarget(withStageDialogue(current));
		}
		return withOneClick(shown.build(), current);
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

	/** The carried item to use until one is picked, then the way's object it is used on. */
	private Highlights useOnWayIn(Highlights.HighlightsBuilder shown, StepIndex.WayIn way)
	{
		List<String> use = List.of(USE_OPTION);
		if (client.isWidgetSelected())
		{
			return shown.menuTarget(MenuTarget.builder().objectIds(Set.of(way.getObject())).path(use).build()).build();
		}
		PlayerSnapshot carried = player.getSnapshot();
		Set<Integer> items = carried == null ? Set.of() : way.getUse().stream().filter(carried.getInventory()::containsKey)
			.collect(Collectors.toUnmodifiableSet());
		return shown.items(items).menuTarget(MenuTarget.builder().itemIds(items).path(use).build()).tab(StepGuide.Tab.INVENTORY).build();
	}

	/** How the act's carried gear goes on ("Wear" on a Max cape, whose left click teleports); null for items not worn. */
	private String wearOption(Set<Integer> items)
	{
		return player.inventoryIds().stream().filter(items::contains).map(leftClicks::wearOption).filter(Objects::nonNull)
			.findFirst().orElse(null);
	}

	/** The act's click, then its chat options in turn. */
	private static MenuTarget withStageDialogue(SubStep current)
	{
		List<String> path = new ArrayList<>(List.of(current.getOption()));
		path.addAll(current.getStage().getDialogue());
		return MenuTarget.builder().npcIds(current.getNpcIds()).objectIds(current.getObjectIds()).path(List.copyOf(path)).build();
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
		if (way == null || way.getDialogue().isEmpty())
		{
			return target;
		}
		List<String> path = new ArrayList<>(target.getPath());
		path.addAll(way.getDialogue());
		return target.toBuilder().path(List.copyOf(path)).build();
	}

	/** Shortest Path names objects and NPCs alike by id, so the id is looked for as both. */
	private static MenuTarget wayTarget(int id, String option)
	{
		Set<Integer> ids = id < 0 ? Set.of() : Set.of(id);
		return MenuTarget.builder().objectIds(ids).npcIds(ids).path(option == null || option.isEmpty() ? List.of() : List.of(option)).build();
	}

	/** A spell, the Minigame Teleport spell, house furniture or a spirit tree, a named item, or the held item with its menu. */
	private void teleportHighlights(Highlights.HighlightsBuilder shown, SubStep current)
	{
		CatalogRow row = current.getRow();
		if (isSpell(row))
		{
			shown.spellWidget(rowTargets.spellWidget(row)).tab(StepGuide.Tab.MAGIC);
		}
		else if (row.getCategory() == Category.MINIGAME)
		{
			shown.spellWidget(rowTargets.minigameSpellWidget(player.currentBook())).tab(StepGuide.Tab.MAGIC)
				.menuTarget(MenuTarget.minigame(row.minigameName()));
		}
		else if (row.isLocated())
		{
			MenuTarget target = rowTargets.locatedTeleport(current);
			boolean set = obelisk.isSetFor(row.getName());
			shown.menuTarget(set ? target.toBuilder().path(List.of(TELEPORT_TO_DESTINATION)).build() : target);
		}
		else if (current.getActItemNamed() != null)
		{
			shown.tab(StepGuide.Tab.INVENTORY);
		}
		else
		{
			Set<Integer> held = withHeld(shown, rowTargets.heldUses(row));
			shown.menuWidget(ScrollBook.entry(row.getName())).menuTarget(rowTargets.heldTeleport(current, held));
		}
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
		Set<Integer> items = held.stream().map(ItemUse::getItemId).collect(Collectors.toUnmodifiableSet());
		boolean worn = !held.isEmpty() && held.stream().allMatch(use -> use.getContainer() == Container.WORN);
		shown.heldUses(held).items(items)
			.tab(held.isEmpty() ? StepGuide.Tab.NONE : worn ? StepGuide.Tab.EQUIPMENT : StepGuide.Tab.INVENTORY);
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
		if (menuTarget.getLine() == MenuTarget.Line.QUETZAL)
		{
			return null;
		}
		if (menuTarget.destination() != null)
		{
			return menuTarget.destination();
		}
		List<String> path = current.getRow() == null ? List.of() : rowTargets.optionPath(current.getRow());
		return path.isEmpty() ? null : path.get(path.size() - 1);
	}

	private Set<Integer> oneClickItems(Highlights shown, SubStep current, String label)
	{
		boolean signals = shown.getMenuTarget().getLine() == MenuTarget.Line.QUETZAL;
		boolean oneDestination = label == null && rowTargets.hasOneDestination(current);
		return shown.getHeldUses().stream()
			.filter(use -> signals ? RowTargets.SIGNAL_OPTION.equalsIgnoreCase(leftClicks.item(use))
				: oneDestination ? isOwnTeleport(leftClicks.item(use)) : teleportsTo(leftClicks.swappedOrLearned(use), label))
			.map(ItemUse::getItemId)
			.collect(Collectors.toUnmodifiableSet());
	}

	/** The carried items the emote step wants on, refreshed every tick as they are put on. */
	private Highlights withEquipItems(Highlights shown, SubStep current, Step step)
	{
		if (current == null || current.getKind() != SubStep.Kind.ACT || current.getDone() != SubStep.Done.EQUIPPED
			|| step == null || step.getRequirements() == null)
		{
			return shown;
		}
		Set<Integer> wanted = player.inventoryIds().stream()
			.filter(id -> step.getRequirements().getItems().stream().anyMatch(need -> need.fulfilledBy(id)))
			.collect(Collectors.toUnmodifiableSet());
		return shown.toBuilder().items(wanted)
			.fillItems(wanted.stream().filter(leftClicks::wears).collect(Collectors.toUnmodifiableSet())).build();
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
			.collect(Collectors.toUnmodifiableSet());
		String option = current.getActOption();
		Highlights.HighlightsBuilder next = shown.toBuilder().items(named)
			.fillItems(option == null ? named : named.stream().filter(id -> option.equalsIgnoreCase(leftClicks.item(id))).collect(Collectors.toUnmodifiableSet()))
			.tab(named.isEmpty() ? shown.getTab() : StepGuide.Tab.INVENTORY);
		return option == null ? next.build() : takeOutFromBook(next, named, current, option);
	}

	/** The book's entry for the scroll, outlined once the book is open, and its Remove option coloured. */
	private Highlights takeOutFromBook(Highlights.HighlightsBuilder shown, Set<Integer> items, SubStep current, String option)
	{
		boolean fromBook = current.getDone() == SubStep.Done.ITEM_CARRIED && current.getRow() != null;
		boolean bookOpen = fromBook && Widgets.shown(client, InterfaceID.Bookofscrolls.CONTENTS);
		return shown.menuWidget(fromBook ? ScrollBook.entry(current.getRow().getName()) : ScrollBook.NO_WIDGET)
			.menuTarget(bookOpen
				? MenuTarget.builder().widgetIds(ScrollBook.widgets(current.getRow().getName())).path(List.of(ScrollBook.REMOVE_OPTION)).build()
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
	private static final Set<String> NOT_TELEPORTS = Set.of("wield", "wear", "equip", "remove", "use", "drop", "examine", "check",
		"inspect", "read");

	/**
	 * A list opener's click only opens the list, unless the row ends on that very option ("Quest point cape: Teleport"):
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
	 * An item with one destination names no option in its row ("Skull sceptre" left-clicks "Invoke" or "Teleport"), so
	 * any left click that is an action of its own is its teleport: with one destination there is no list to open.
	 */
	private static boolean isOwnTeleport(String option)
	{
		return option != null && !NOT_TELEPORTS.contains(MenuLabels.normalise(option));
	}
}
