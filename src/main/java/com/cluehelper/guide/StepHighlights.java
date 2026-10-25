package com.cluehelper.guide;

import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.Container;
import com.cluehelper.player.ItemUse;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.route.Exit;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepIndex;
import com.cluehelper.step.StepRequirements;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.Category;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;
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
	private static final String SEARCH_OPTION = "Search";
	private static final String TELEPORT_TO_DESTINATION = "Teleport to Destination";
	private static final Set<Integer> FAIRY_RING_OBJECTS = Set.of(ObjectID.FAIRYRING_HOMEHUB, ObjectID.FAIRYRING_MINORHUB,
		ObjectID.POH_FAIRY_RING, ObjectID.POH_SPIRIT_RING);

	private final Client client;
	private final PlayerModel player;
	private final BankMemory bankMemory;
	private final LeftClicks leftClicks;
	private final RowTargets rowTargets;
	private final TaskActs taskActs;
	private final DepositReminder deposit;
	private final ObeliskDestination obelisk;

	@Inject
	StepHighlights(Client client, PlayerModel player, BankMemory bankMemory, LeftClicks leftClicks, RowTargets rowTargets,
		TaskActs taskActs, DepositReminder deposit, ObeliskDestination obelisk)
	{
		this.client = client;
		this.player = player;
		this.bankMemory = bankMemory;
		this.leftClicks = leftClicks;
		this.rowTargets = rowTargets;
		this.taskActs = taskActs;
		this.deposit = deposit;
		this.obelisk = obelisk;
	}

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
				return shown.menuTarget(enterTarget(current)).build();
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
		if (objectOption != null)
		{
			return objects.stream().filter(object -> leftClicks.isLeftClick(object, objectOption))
				.collect(Collectors.toUnmodifiableList());
		}
		MenuTarget menuTarget = shown.getMenuTarget();
		boolean ring = menuTarget.getLine() == MenuTarget.Line.FAIRY_CODE && menuTarget.getLineKey() != null;
		String firstClick = menuTarget.getPath().isEmpty() ? null : menuTarget.getPath().get(0);
		String label = shown.getOneClickLabel();
		return objects.stream()
			.filter(object -> ring ? ringGoesTo(leftClicks.object(object), menuTarget.getLineKey())
				: oneClickTo(leftClicks.object(object), label, firstClick))
			.collect(Collectors.toUnmodifiableList());
	}

	List<NPC> fillNpcs(List<NPC> npcs, String npcOption)
	{
		return npcOption == null ? List.of()
			: npcs.stream().filter(npc -> npcOption.equalsIgnoreCase(leftClicks.npc(npc))).collect(Collectors.toUnmodifiableList());
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
			boolean equips = current.getDone() == SubStep.Done.WAY_IN_WORN || current.getDone() == SubStep.Done.GEAR_WORN;
			return shown.items(current.getActItems())
				.fillItems(equips ? current.getActItems().stream().filter(leftClicks::wears).collect(Collectors.toUnmodifiableSet())
					: current.getActItems())
				.tab(current.getDone() == SubStep.Done.SLOTS_EMPTY ? StepGuide.Tab.EQUIPMENT : StepGuide.Tab.INVENTORY).build();
		}
		String planted = taskActs.plantedTreeLine(current, step);
		if (planted != null)
		{
			shown.menuTarget(rowTargets.plantedTree(current.getObjectIds(), planted));
		}
		return withOneClick(shown.build(), current);
	}

	/** The way in's object with its option and dialogue; once its items are picked up, the object they are used on. */
	private static MenuTarget enterTarget(SubStep current)
	{
		StepIndex.PickUp pickUp = current.getWayIn().getPickUp();
		boolean pickedUp = pickUp != null && current.getText().startsWith(StepGuide.KEEP_GOING);
		return pickedUp ? MenuTarget.builder().objectIds(Set.copyOf(pickUp.getObjects())).path(List.of(pickUp.getOption())).build()
			: withDialogue(wayTarget(current.getWayIn().getObject(), current.getWayIn().getOption()), current.getWayIn());
	}

	/** The step's hand-written way in that this exit row is, or null. */
	private static StepIndex.WayIn wayInOf(Step step, Exit exit)
	{
		StepIndex.WayIn way = step == null ? null : step.getData().getEntry();
		return way != null && exit.getOrigin().equals(way.getTile()) && exit.getDestination().equals(way.getTo()) ? way : null;
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
				: oneDestination ? OneClick.isOwnTeleport(leftClicks.item(use)) : OneClick.teleportsTo(leftClicks.swappedOrLearned(use), label))
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
		Widget book = fromBook ? client.getWidget(InterfaceID.Bookofscrolls.CONTENTS) : null;
		boolean bookOpen = book != null && !book.isHidden();
		return shown.menuWidget(fromBook ? ScrollBook.entry(current.getRow().getName()) : ScrollBook.NO_WIDGET)
			.menuTarget(bookOpen
				? MenuTarget.builder().widgetIds(ScrollBook.widgets(current.getRow().getName())).path(List.of(ScrollBook.REMOVE_OPTION)).build()
				: rowTargets.items(items, option))
			.build();
	}

	private static boolean oneClickTo(String leftClick, String label, String firstClick)
	{
		return OneClick.teleportsTo(leftClick, label) || firstClick != null && MenuLabels.matches(leftClick, firstClick);
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
}
