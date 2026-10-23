package com.cluehelper.guide;

import com.cluehelper.hotcold.ProbeTarget;
import com.cluehelper.link.ClueLink;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.Book;
import com.cluehelper.player.Container;
import com.cluehelper.player.HouseState;
import com.cluehelper.player.ItemUse;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.RuneModel;
import com.cluehelper.player.Spell;
import com.cluehelper.route.Exit;
import com.cluehelper.route.GearPicker;
import com.cluehelper.route.GearRequirement;
import com.cluehelper.route.Leg;
import com.cluehelper.route.Plan;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.RequiredItems;
import com.cluehelper.route.StepNeeds;
import com.cluehelper.route.Route;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.route.SceneBfs;
import com.cluehelper.route.WalkData;
import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.EmptySlots;
import com.cluehelper.step.Stage;
import com.cluehelper.step.StageTarget;
import com.cluehelper.step.StepIndex;
import com.cluehelper.step.Step;
import com.cluehelper.transport.AvailabilityEngine;
import com.cluehelper.transport.AvailabilityService;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.Category;
import com.cluehelper.transport.HouseNeed;
import com.cluehelper.transport.RowAvailability;
import com.cluehelper.transport.SpellbookChange;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.WorldView;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.ItemContainer;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.cluescrolls.clues.item.AllRequirementsCollection;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;
import net.runelite.client.util.Text;

/**
 * Client thread only. While a teleport or book change is under way, a new route waits: the cast spends runes or starts
 * a cooldown, which re-plans from the old tile before the landing.
 */
@Slf4j
@Singleton
public class StepGuide
{
	public enum Tab
	{
		NONE, INVENTORY, EQUIPMENT, MAGIC, EMOTES, MUSIC
	}

	private static final int NO_STASH = -1;
	private static final String SEARCH_OPTION = "Search";
	private static final Set<String> WEAR_OPTIONS = Set.of("Wear", "Wield", "Equip", "Hold");
	private static final String USE_OPTION = "Use";
	private static final int MAX_STACK = Integer.MAX_VALUE / 2;

	/** Farther than running moves in a tick: a teleport, a ladder, a boat. */
	static final int JUMP_TILES = 4;
	/** A refused book change leaves the book as it was; the next tick shows whether it changed. */
	static final int REFUSAL_CHECK_TICKS = 2;
	/** A teleport animates on its click's tick or within two: three ticks without one means it was refused. */
	static final int TELEPORT_REFUSAL_TICKS = 3;
	private static final int PARAM_SPELL_WIDGET = 596;
	private static final String CAPE_LIMIT_TEXT = "five times per day";
	private static final int NO_ANIMATION = -1;
	private static final int NEVER = Integer.MIN_VALUE;
	private static final int OBJECT_REFRESH_TICKS = 5;
	/** A bank node sits on the walkable tile in front of its booth, chest or banker. */
	private static final int BANK_TILES = 3;
	/** How far from its boarding tile a ride's NPC may stand. */
	private static final int RIDE_NPC_TILES = 8;
	/** The patch objects are multilocs on their transmit varbits; the grown tree is the form they show. */
	private static final Set<Integer> PLANTED_SPIRIT_TREES = Set.of(ObjectID.FARMING_SPIRIT_TREE_PATCH_1,
		ObjectID.FARMING_SPIRIT_TREE_PATCH_2, ObjectID.FARMING_SPIRIT_TREE_PATCH_3, ObjectID.FARMING_SPIRIT_TREE_PATCH_4,
		ObjectID.FARMING_SPIRIT_TREE_PATCH_5, ObjectID.SPIRIT_TREE_FULLYGROWN);
	private static final String TRAVEL_OPTION = "Travel";
	private static final String TREE_OPTION = "Tree";
	private static final String SIGNAL_OPTION = "Signal";
	private static final Set<Integer> FAIRY_RING_OBJECTS = Set.of(ObjectID.FAIRYRING_HOMEHUB, ObjectID.FAIRYRING_MINORHUB,
		ObjectID.POH_FAIRY_RING, ObjectID.POH_SPIRIT_RING);
	private static final String SPELLBOOK_OPTION = "Spellbook";
	/** Catalog landings spread 0–2 tiles, so another menu's line within this lands at the same place. */
	private static final int SAME_LANDING_TILES = 3;
	/** Minigame Teleport is a spell on every book, so it is cast rather than picked from the Grouping tab. */
	private static final Map<Book, Integer> MINIGAME_SPELLS = Map.of(
		Book.STANDARD, InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_STANDARD,
		Book.ANCIENT, InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_ANCIENT,
		Book.LUNAR, InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_LUNAR,
		Book.ARCEUUS, InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_ARCEUUS);
	/**
	 * Core's tile can be on another floor than its NPC (Donovan stands upstairs, his tile is on the ground floor), so a
	 * reached act stays while the player is this close to a target on any plane.
	 */
	static final int STAY_AT_ACT_TILES = 20;
	/** In scene-walk tiles: 20 ticks of running. */
	static final int ARRIVED_WALK_TILES = 40;
	private static final int DEPOSIT_TILES = 15;

	private final Client client;
	private final ClueLink clueLink;
	private final RoutePlanner routes;
	private final PlayerModel player;
	private final BankMemory bankMemory;
	private final MenuLines menuLines;
	private final AvailabilityService availability;
	private final ProbeTarget probeTarget;
	private final StageTarget stageTarget;
	private final ActWatcher actWatcher;
	private final StepNeeds stepNeeds;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	/** When the guided step began: an act's event counts from then. */
	private int stepSince = ActWatcher.NEVER;
	private static final String TALK_TO = "Talk to ";
	private static final List<String> BOXES = List.of("puzzle box", "light box");
	private SubStep fightView;
	/** After a talk that hands over a puzzle or light box: open it (core solves it), then talk again. */
	private SubStep boxView;
	private SubStep afterFight;
	/** The STASH unit to put the last emote step's items back into; null once done or walked away from. */
	private WorldPoint depositAt;
	private boolean depositBeenNear;
	private int depositSince = ActWatcher.NEVER;
	private int depositStashId = NO_STASH;
	/** The STASH items the player still carries, to put back. */
	private List<String> depositItems = List.of();
	private final Map<String, Integer> spellWidgets = new HashMap<>();

	private Runnable unregister;
	private Route route;
	private Step routeStep;
	private String actReachedStepKey;
	private List<SubStep> steps = List.of();
	private int index;
	private Route waiting;
	private WorldPoint waitingFrom;
	private boolean jumpedThisSubStep;
	private WorldPoint lastTile;
	private String refusalCandidate;
	private Book refusalBook;
	private int refusalCheckTick;
	private Set<Integer> highlightItems = Set.of();
	private Set<Integer> fillItems = Set.of();
	private final LeftClicks leftClicks = new LeftClicks();
	private final SwapperClicks swapperClicks;
	private List<ItemUse> heldUses = List.of();
	private String oneClickLabel;
	/** An outlined object is filled when its left click is this option (a STASH unit's "Search"); null for teleports. */
	private String objectOption;
	private Set<Integer> bankHighlightItems = Set.of();
	private int highlightSpellWidget = -1;
	private int highlightMenuWidget = ScrollBook.NO_WIDGET;
	private Tab highlightTab = Tab.NONE;
	private MenuTarget menuTarget = MenuTarget.NONE;
	/** The current sub-step's own object or NPC with its option ("Search for traps"), for colouring its menu line. */
	private MenuTarget optionTarget = MenuTarget.NONE;
	private List<WorldPoint> pickUpTiles = List.of();
	/** The obelisk's list was open on the target's line: its closing means the destination is set. */
	private boolean destinationListSeen;
	private String destinationSetFor;
	private List<TileObject> highlightObjects = List.of();
	private List<NPC> highlightNpcs = List.of();
	private List<TileObject> fillObjects = List.of();
	private List<NPC> fillNpcs = List.of();
	/** The option an outlined NPC is filled for when it is its left click (the Guardian mummy's Start-minigame). */
	private String npcOption;
	private int objectsFoundTick = NEVER;
	/**
	 * A house's furniture appears a tick or two after the load, so empty searches repeat every tick at first, then every
	 * {@link #OBJECT_REFRESH_TICKS}.
	 */
	private int emptySearches;
	/** A game message right after a teleport click, with no animation, means the game refused it. */
	private int teleportClickTick = NEVER;
	private String teleportRefusal;
	private int teleportCheckTick = NEVER;
	private WorldPoint teleportClickTile;

	@Inject
	StepGuide(Client client, ClueLink clueLink, RoutePlanner routes, PlayerModel player, BankMemory bankMemory, MenuLines menuLines,
		AvailabilityService availability, SwapperClicks swapperClicks, ProbeTarget probeTarget, StageTarget stageTarget,
		ActWatcher actWatcher, StepNeeds stepNeeds)
	{
		this.actWatcher = actWatcher;
		this.stepNeeds = stepNeeds;
		this.stageTarget = stageTarget;
		this.probeTarget = probeTarget;
		this.swapperClicks = swapperClicks;
		this.menuLines = menuLines;
		this.availability = availability;
		this.bankMemory = bankMemory;
		this.client = client;
		this.clueLink = clueLink;
		this.routes = routes;
		this.player = player;
	}

	public void start()
	{
		unregister = routes.addListener(this::onPlan);
	}

	public void stop()
	{
		if (unregister != null)
		{
			unregister.run();
			unregister = null;
		}
		clear();
	}

	/** Listeners run on the client thread. */
	public Runnable addListener(Runnable listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	public Route getRoute()
	{
		return route;
	}

	/** While an enemy attacks during a dig or an emote, the fight; after it, the act again. */
	public SubStep getCurrent()
	{
		if (fightView != null)
		{
			return fightView;
		}
		if (boxView != null)
		{
			return boxView;
		}
		return index < steps.size() ? steps.get(index) : null;
	}

	/** During a fight, the act to do again. */
	public SubStep getNext()
	{
		if (afterFight != null)
		{
			return afterFight;
		}
		return index + 1 < steps.size() ? steps.get(index + 1) : null;
	}

	public boolean isBankStop()
	{
		SubStep current = getCurrent();
		SubStep next = getNext();
		return current != null && (current.getKind() == SubStep.Kind.BANK
			|| current.getKind() == SubStep.Kind.WALK && next != null && next.getKind() == SubStep.Kind.BANK);
	}

	/**
	 * Walking to a located teleport's object or NPC (Renu, a spirit tree): like a bank, it is outlined from the walk on
	 * and the line leads to it.
	 */
	public boolean isApproach()
	{
		SubStep current = getCurrent();
		SubStep next = getNext();
		boolean toNpc = current != null && current.getKind() == SubStep.Kind.WALK && !current.getNpcIds().isEmpty();
		return toNpc || current != null && current.getKind() == SubStep.Kind.WALK && next != null && next.getKind() == SubStep.Kind.TELEPORT
			&& next.getRow() != null && next.getRow().isLocated();
	}

	/** A staircase, ladder, door or NPC that is the way out or in: outlined and walked to like a bank. */
	public boolean isExit()
	{
		SubStep current = getCurrent();
		return current != null && (current.getKind() == SubStep.Kind.EXIT || current.getKind() == SubStep.Kind.ENTER);
	}

	/** Shortest Path names objects and NPCs alike by id, so the id is looked for as both. */
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

	/** The step's hand-written way in that this exit row is, or null. */
	private StepIndex.WayIn wayInOf(WorldPoint origin, WorldPoint destination)
	{
		StepIndex.WayIn way = routeStep == null || routeStep.getData() == null ? null : routeStep.getData().getEntry();
		return way != null && origin.equals(way.getTile()) && destination.equals(way.getTo()) ? way : null;
	}

	private static MenuTarget wayTarget(int id, String option)
	{
		Set<Integer> ids = id < 0 ? Set.of() : Set.of(id);
		return MenuTarget.builder().objectIds(ids).npcIds(ids).path(option == null || option.isEmpty() ? List.of() : List.of(option)).build();
	}

	public List<SubStep> getSteps()
	{
		return steps;
	}

	public Set<Integer> getHighlightItems()
	{
		return highlightItems;
	}

	public Set<Integer> getBankHighlightItems()
	{
		return bankHighlightItems;
	}

	public Tab getHighlightTab()
	{
		return highlightTab;
	}

	public int getHighlightMenuWidget()
	{
		return highlightMenuWidget;
	}

	public MenuTarget getMenuTarget()
	{
		return menuTarget;
	}

	public MenuTarget getOptionTarget()
	{
		return optionTarget;
	}

	public List<TileObject> getHighlightObjects()
	{
		return highlightObjects;
	}

	public Set<Integer> getFillItems()
	{
		return fillItems;
	}

	public List<TileObject> getFillObjects()
	{
		return fillObjects;
	}

	public List<NPC> getFillNpcs()
	{
		return fillNpcs;
	}

	public List<NPC> getHighlightNpcs()
	{
		return highlightNpcs;
	}

	public int getHighlightSpellWidget()
	{
		return highlightSpellWidget;
	}

	/** An interface option the task picks and its confirm button, outlined while the interface is open. */
	public List<Widget> getHighlightChoice()
	{
		Widget product = makeOption();
		if (product != null)
		{
			return List.of(product);
		}
		StepIndex.Choose choose = taskChoose();
		Widget root = choose == null ? null : client.getWidget(choose.getRoot());
		if (root == null || root.isHidden())
		{
			return List.of();
		}
		Widget option = withText(root, choose.getText());
		List<Widget> widgets = new ArrayList<>();
		if (option != null)
		{
			widgets.add(option.getParent() != null ? option.getParent() : option);
		}
		Widget confirm = client.getWidget(choose.getConfirm());
		if (confirm != null && !confirm.isHidden())
		{
			widgets.add(confirm);
		}
		return List.copyOf(widgets);
	}

	/** The making step's product in the "How many do you wish to make?" box: its option, A to R. */
	private Widget makeOption()
	{
		Widget box = client.getWidget(InterfaceID.Skillmulti.UNIVERSE);
		StepIndex.Making making = box == null || box.isHidden() || routeStep == null ? null : makingNow(carriedItemIds());
		if (making == null || making.getMake() < 0)
		{
			return null;
		}
		for (int id = InterfaceID.Skillmulti.A; id <= InterfaceID.Skillmulti.R; id++)
		{
			Widget option = client.getWidget(id);
			if (option != null && !option.isHidden()
				&& MenuLines.descendants(option).stream().anyMatch(shown -> shown.getItemId() == making.getMake()))
			{
				return option;
			}
		}
		return null;
	}

	private StepIndex.Choose taskChoose()
	{
		SubStep current = getCurrent();
		boolean task = current != null && current.getKind() == SubStep.Kind.ACT && current.getStage() != null
			&& current.getStage().getKind() == Stage.Kind.TASK;
		return task && routeStep != null && routeStep.getData() != null ? routeStep.getData().getChoose() : null;
	}

	private static Widget withText(Widget parent, String text)
	{
		if (parent.getText() != null && Text.removeTags(parent.getText()).equalsIgnoreCase(text))
		{
			return parent;
		}
		for (Widget[] children : new Widget[][]{parent.getStaticChildren(), parent.getDynamicChildren(), parent.getNestedChildren()})
		{
			for (Widget child : children == null ? new Widget[0] : children)
			{
				Widget found = child.isHidden() ? null : withText(child, text);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private void onPlan(Plan plan)
	{
		PlanStart start = routes.getStart();
		Step step = stageTarget.travelStep(probeTarget.travelStep(clueLink.getCurrentStep()));
		boolean noWalk = start != null && start.getKind() == PlanStart.Kind.TELEPORT_ONLY;
		boolean partWay = start != null && step != null && step.getData() != null && step.getData().getEntry() != null
			&& step.getData().getEntry().isPartWay(start.getTile());
		SubStep goIn = noWalk || partWay ? SubSteps.goingIn(step, start.getTile()) : null;
		boolean inside = noWalk && goIn == null && isInsideWayIn(step, start.getTile());
		if (goIn != null || inside)
		{
			// On a way in with no walk nodes (the Agility Pyramid's course and top): keep going, never teleport out
			if (route != null || routeStep == null || !routeStep.getKey().equals(step.getKey()) || steps.isEmpty())
			{
				route = null;
				routeStep = step;
				steps = inWayIn(step, goIn);
				index = 0;
				updateHighlights();
				refreshObjects();
				notifyListeners();
			}
			return;
		}
		Route committed = routes.getCommitted();
		if (isUnderWay())
		{
			waiting = committed;
			waitingFrom = player.getPosition().getTile();
			return;
		}
		follow(committed);
	}

	/** Call from a PostMenuSort handler that runs after Menu Entry Swapper's. */
	public void onPostMenuSort()
	{
		if (client.isMenuOpen() || client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (entries.length > 0 && leftClicks.learn(entries[entries.length - 1]))
		{
			refreshItemFill();
			fillObjects = oneClickObjects(highlightObjects);
			fillNpcs = oneClickNpcs(highlightNpcs);
		}
	}

	/** An act's items are filled by what it asks for (equip on a left click, its own items), not by a teleport's left click. */
	private void refreshItemFill()
	{
		SubStep current = getCurrent();
		if (current != null && current.getKind() == SubStep.Kind.ACT)
		{
			refreshEquipItems();
			return;
		}
		fillItems = oneClickItems(oneClickLabel);
	}

	/** Call from a GameTick handler that runs after the planner's. */
	public void onGameTick()
	{
		WorldPoint tile = player.getPosition().getTile();
		boolean jumped = tile != null && lastTile != null
			&& (tile.getPlane() != lastTile.getPlane() || tile.distanceTo2D(lastTile) > JUMP_TILES);
		lastTile = tile;
		jumpedThisSubStep |= jumped;
		if (jumped)
		{
			actReachedStepKey = null;
		}
		boolean changed = false;
		if (waiting != null && !isUnderWay())
		{
			Route next = waiting;
			boolean stale = waitingFrom != null && tile != null && !SubSteps.near(tile, waitingFrom, JUMP_TILES);
			waiting = null;
			if (!stale)
			{
				follow(next);
				return;
			}
		}
		if (routeStep != null && !isAtStep() && arrived(routeStep))
		{
			follow(route);
			return;
		}
		actWatcher.onGameTick(routeStep);
		checkDeposit(tile);
		changed |= advance(tile);
		changed |= refreshFight();
		changed |= refreshBox();
		changed |= watchDestinationSet();
		changed |= keyFromCharterList();
		changed |= partWayIn(tile);
		changed |= pickUpForAct(tile);
		changed |= equipWhatIsCarried();
		changed |= equipGear();
		changed |= removeGear();
		changed |= showMaking();
		checkRefusal();
		checkTeleportRefusal();
		if (changed)
		{
			updateHighlights();
		}
		refreshObjects();
		refreshEquipItems();
		refreshTaskItems();
		refreshNamedItems();
		if (changed)
		{
			notifyListeners();
		}
	}

	private void refreshObjects()
	{
		SubStep current = getCurrent();
		boolean bank = isBankStop();
		List<String> option = current == null || current.getOption() == null ? List.of() : List.of(current.getOption());
		MenuTarget target = current != null && !current.getNpcIds().isEmpty() ? MenuTarget.builder().npcIds(current.getNpcIds()).path(option).build()
			: current != null && !current.getObjectIds().isEmpty() ? MenuTarget.builder().objectIds(current.getObjectIds()).path(option).build()
			: isApproach() ? houseTarget(getNext().getRow()) : stashAhead() != NO_STASH ? MenuTarget.builder().objectIds(Set.of(stashAhead())).build()
			: menuTarget;
		optionTarget = option.isEmpty() || menuTarget != MenuTarget.NONE ? MenuTarget.NONE : target;
		Set<Integer> ids = target.getObjectIds();
		Set<Integer> npcIds = target.getNpcIds();
		if (ids.isEmpty() && npcIds.isEmpty() && !bank)
		{
			highlightObjects = List.of();
			highlightNpcs = List.of();
			fillObjects = List.of();
		fillNpcs = List.of();
			fillNpcs = List.of();
			return;
		}
		int tick = client.getTickCount();
		boolean nothingYet = highlightObjects.isEmpty() && highlightNpcs.isEmpty() && emptySearches < OBJECT_REFRESH_TICKS;
		if (objectsFoundTick == NEVER || nothingYet || tick - objectsFoundTick >= OBJECT_REFRESH_TICKS)
		{
			emptySearches = objectsFoundTick == NEVER ? 0 : emptySearches + 1;
			objectsFoundTick = tick;
			highlightObjects = bank ? SceneObjects.banksNear(client, current.getWaypoint(), BANK_TILES) : SceneObjects.find(client, ids);
			highlightNpcs = bank ? SceneObjects.bankersNear(client, current.getWaypoint(), BANK_TILES) : npcsFor(target);
			fillObjects = bank ? List.of() : oneClickObjects(highlightObjects);
			npcOption = bank || target.getPath().isEmpty() ? null : target.getPath().get(0);
			fillNpcs = oneClickNpcs(highlightNpcs);
		}
	}

	/**
	 * Shortest Path names one NPC per ride (a charter's Trader Crewmember), but a port's crew have several ids: when the
	 * named one isn't there, an NPC of the same name near the boarding tile with the ride's option is it, and its menu is
	 * coloured too.
	 */
	private List<NPC> npcsFor(MenuTarget target)
	{
		List<NPC> named = SceneObjects.npcs(client, target.getNpcIds());
		WorldPoint boarding = isApproach() ? getNext().getWaypoint() : getCurrent().getWaypoint();
		if (!named.isEmpty() || target.getNpcIds().isEmpty() || target.getPath().isEmpty() || boarding == null)
		{
			return named;
		}
		String name = client.getNpcDefinition(target.getNpcIds().iterator().next()).getName();
		List<NPC> crew = SceneObjects.npcsNear(client, boarding, RIDE_NPC_TILES, target.getPath().get(0)).stream()
			.filter(npc -> npc.getName() != null && npc.getName().equalsIgnoreCase(name)).collect(Collectors.toList());
		if (!crew.isEmpty() && menuTarget.getNpcIds().equals(target.getNpcIds()))
		{
			Set<Integer> ids = new HashSet<>(target.getNpcIds());
			crew.forEach(npc -> ids.add(npc.getId()));
			menuTarget = menuTarget.toBuilder().npcIds(Set.copyOf(ids)).build();
		}
		return crew;
	}

	private static boolean isInsideWayIn(Step step, WorldPoint at)
	{
		StepIndex.WayIn way = step == null || step.getData() == null ? null : step.getData().getEntry();
		return way != null && way.isInside(at);
	}

	/** Going in (or already in): the rest of the way, then the clue's own sub-steps. */
	private List<SubStep> inWayIn(Step step, SubStep goIn)
	{
		List<SubStep> rest = new ArrayList<>();
		if (goIn != null)
		{
			rest.add(goIn);
		}
		if (!step.getTargets().isEmpty())
		{
			rest.add(SubStep.builder().kind(SubStep.Kind.WALK_TO_CLUE).text("Walk to the clue").goal(step.getTargets().get(0))
				.radius(SubSteps.ARRIVE_TILES).build());
		}
		rest.addAll(Acts.of(step, takeFromStash(step)));
		return SubSteps.withWayInEquipped(rest, step.getData());
	}

	/** Part way up a way in (the pyramid's lower levels), the step points on, not back to its ground-floor start. */
	private boolean partWayIn(WorldPoint tile)
	{
		SubStep current = getCurrent();
		boolean onWay = current != null && current.getKind() == SubStep.Kind.ENTER && current.getWayIn().isPartWay(tile);
		pickUpTiles = onWay && current.getWayIn().getPickUp() != null ? groundItemTiles(current.getWayIn().getPickUp().getId()) : List.of();
		if (onWay && current.getWayIn().getPickUp() != null)
		{
			return pickUpOnTheWay(current, current.getWayIn().getPickUp(), tile);
		}
		boolean partWay = onWay && !current.getText().startsWith(KEEP_GOING);
		if (!partWay)
		{
			return false;
		}
		WorldPoint to = current.getWayIn().getTo();
		boolean toNearby = !current.getWayIn().isNoLine() && to != null && tile != null && to.getPlane() == tile.getPlane()
			&& to.distanceTo2D(tile) <= PART_WAY_LINE_TILES;
		List<SubStep> onward = new ArrayList<>(steps);
		onward.set(index, current.toBuilder().text(KEEP_GOING + current.getWayIn().getText()).waypoint(toNearby ? to : null).build());
		steps = List.copyOf(onward);
		return true;
	}

	/** Part way in, the ground items to pick up first (the Mogre Camp's rocks): to the nearest, then on through the way. */
	private boolean pickUpOnTheWay(SubStep current, StepIndex.PickUp pickUp, WorldPoint tile)
	{
		int counted = pickUp.getVarbit() < 0 ? 0 : client.getVarbitValue(pickUp.getVarbit());
		int carried = Math.min(pickUp.getCount(), Math.max(counted, carriedCount(pickUp.getId())));
		boolean done = carried >= pickUp.getCount();
		WorldPoint nearest = tile == null ? null : pickUpTiles.stream().min(Comparator.comparingInt(at -> at.distanceTo2D(tile))).orElse(null);
		SubStep onward = current.toBuilder()
			.text(done ? KEEP_GOING + pickUp.getThen() : "Pick up " + pickUp.getCount() + " " + pickUp.getName() + " (" + carried + "/" + pickUp.getCount() + ")")
			.detail(null).waypoint(done ? current.getWayIn().getTo() : nearest).build();
		if (onward.equals(current))
		{
			return false;
		}
		List<SubStep> updated = new ArrayList<>(steps);
		updated.set(index, onward);
		steps = List.copyOf(updated);
		updateHighlights();
		return true;
	}

	/** An act's ground item (king worms): outlined where it lies, the nearest as the waypoint. */
	private boolean pickUpForAct(WorldPoint tile)
	{
		SubStep current = getCurrent();
		if (current == null || current.getKind() != SubStep.Kind.ACT || current.getGroundItem() < 0)
		{
			return false;
		}
		pickUpTiles = groundItemTiles(current.getGroundItem());
		WorldPoint nearest = tile == null ? null : pickUpTiles.stream().min(Comparator.comparingInt(at -> at.distanceTo2D(tile))).orElse(null);
		return replaceCurrent(current.toBuilder().waypoint(nearest).build());
	}

	private int carriedCount(int itemId)
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		return inventory == null ? 0 : inventory.count(itemId);
	}

	/** Client thread. Every loaded tile on the player's floor with that item on the ground. */
	private List<WorldPoint> groundItemTiles(int itemId)
	{
		WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return List.of();
		}
		List<WorldPoint> found = new ArrayList<>();
		Tile[][] tiles = view.getScene().getTiles()[view.getPlane()];
		for (Tile[] column : tiles)
		{
			for (Tile tile : column)
			{
				List<TileItem> items = tile == null ? null : tile.getGroundItems();
				if (items != null && items.stream().anyMatch(item -> item.getId() == itemId))
				{
					found.add(tile.getWorldLocation());
				}
			}
		}
		return List.copyOf(found);
	}

	/** Ground items to pick up now, by tile. */
	public List<WorldPoint> getPickUpTiles()
	{
		return pickUpTiles;
	}

	/** The charter list's key for the port is known only once it is open: the step's text names it from then on. */
	private boolean keyFromCharterList()
	{
		char key = menuLines.charterKey(menuTarget);
		SubStep current = getCurrent();
		if (key == 0 || current == null || current.getText().contains(MenuLines.keyText(key)))
		{
			return false;
		}
		List<SubStep> keyed = new ArrayList<>(steps);
		keyed.set(index, current.toBuilder().text(SubSteps.withKey(current.getText(), key)).build());
		steps = List.copyOf(keyed);
		return true;
	}

	/** An obelisk takes two clicks: Set Destination and its list line, then Teleport to Destination. */
	private boolean watchDestinationSet()
	{
		List<String> path = menuTarget.getPath();
		SubStep current = getCurrent();
		if (current == null || current.getRow() == null || path.size() != 2 || !SET_DESTINATION.equals(path.get(0)))
		{
			destinationListSeen = false;
			return false;
		}
		if (menuLines.showing(menuTarget))
		{
			destinationListSeen = true;
			return false;
		}
		if (!destinationListSeen)
		{
			return false;
		}
		destinationListSeen = false;
		destinationSetFor = current.getRow().getName();
		updateHighlights();
		return true;
	}

	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING || event.getGameState() == GameState.HOPPING)
		{
			highlightObjects = List.of();
			highlightNpcs = List.of();
			fillObjects = List.of();
		fillNpcs = List.of();
			fillNpcs = List.of();
			objectsFoundTick = NEVER;
		}
		else if (event.getGameState() == GameState.LOGGED_IN)
		{
			objectsFoundTick = NEVER;
			refreshObjects();
		}
	}

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		SubStep current = getCurrent();
		if (current == null || current.getKind() != SubStep.Kind.TELEPORT || current.getRow() == null)
		{
			return;
		}
		boolean onItem = menuTarget.getItemIds().contains(event.getItemId())
			|| menuTarget.getItemNames().contains(MenuLabels.normalise(event.getMenuTarget()));
		boolean onObject = menuTarget.getObjectIds().contains(event.getId());
		if (onItem || onObject)
		{
			teleportClickTick = client.getTickCount();
			teleportClickTile = player.getPosition().getTile();
		}
	}

	/** MESBOX lines during a book change are the refusal backstop. */
	public void onChatMessage(ChatMessage message)
	{
		SubStep current = getCurrent();
		boolean gameLine = message.getType() == ChatMessageType.GAMEMESSAGE || message.getType() == ChatMessageType.MESBOX;
		if (gameLine && current != null && current.getKind() == SubStep.Kind.TELEPORT && current.getRow() != null
			&& teleportClickTick != NEVER && client.getTickCount() - teleportClickTick <= 1)
		{
			teleportRefusal = current.getRow().getName();
			teleportCheckTick = client.getTickCount() + TELEPORT_REFUSAL_TICKS;
		}
		if (message.getType() != ChatMessageType.MESBOX || current == null || current.getChange() == null)
		{
			return;
		}
		String text = Text.removeTags(message.getMessage());
		if (text.contains(CAPE_LIMIT_TEXT))
		{
			routes.onCapeChangesRefused();
			return;
		}
		refusalCandidate = current.getChange().getName();
		refusalBook = current.getBook();
		refusalCheckTick = client.getTickCount() + REFUSAL_CHECK_TICKS;
	}

	private void checkTeleportRefusal()
	{
		if (teleportRefusal == null || client.getTickCount() < teleportCheckTick)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		SubStep current = getCurrent();
		WorldPoint tile = player.getPosition().getTile();
		boolean sameStep = current != null && current.getRow() != null && teleportRefusal.equals(current.getRow().getName());
		boolean stayed = tile != null && teleportClickTile != null && SubSteps.near(tile, teleportClickTile, JUMP_TILES);
		if (sameStep && stayed && local != null && local.getAnimation() == NO_ANIMATION)
		{
			log.debug("teleport refused: {}", teleportRefusal);
			routes.onTeleportRefused(teleportRefusal);
		}
		teleportRefusal = null;
		teleportClickTick = NEVER;
	}

	private void checkRefusal()
	{
		if (refusalCandidate == null || client.getTickCount() < refusalCheckTick)
		{
			return;
		}
		if (client.getVarbitValue(VarbitID.SPELLBOOK) != refusalBook.ordinal())
		{
			routes.onChangeRefused(refusalCandidate);
		}
		refusalCandidate = null;
	}

	private void follow(Route committed)
	{
		Step step = stageTarget.travelStep(probeTarget.travelStep(clueLink.getCurrentStep()));
		WorldPoint tile = player.getPosition().getTile();
		noteDeposit(routeStep, step);
		if (routeStep == null || step == null || !routeStep.getKey().equals(step.getKey()))
		{
			stepSince = client.getTickCount();
		}
		route = committed;
		routeStep = step;
		boolean atStep = step != null && (stillAtAct(step, tile) || arrived(step));
		List<SubStep> base = step == null || committed == null && !atStep ? List.of() : atStep ? actsHere(step, tile)
			: SubSteps.of(committed, step, this::withdrawLine, house(), this::exitOf, takeFromStash(step),
				routes.keepsScrollBookOutOfWilderness(), carriesItemNamed(SCROLL_BOOK_NAME));
		steps = withDeposit(base);
		index = 0;
		jumpedThisSubStep = false;
		advance(tile);
		showMaking();
		updateHighlights();
		refreshObjects();
		notifyListeners();
	}

	private boolean refreshFight()
	{
		SubStep act = index < steps.size() ? steps.get(index) : null;
		SubStep view = null;
		SubStep again = null;
		if (act != null && act.isFights())
		{
			NPC enemy = actWatcher.getEnemy();
			if (enemy != null)
			{
				boolean emote = act.getDone() == SubStep.Done.URI_HERE;
				view = SubStep.builder().kind(SubStep.Kind.ACT).text("Kill the " + (enemy.getName() == null ? "enemy" : enemy.getName()))
					.detail(emote ? "Stay equipped" : null).build();
				again = act.toBuilder().text(act.getAgain()).build();
			}
			else if (actWatcher.foughtSince(stepSince))
			{
				view = act.toBuilder().text(act.getAgain()).build();
			}
		}
		boolean changed = !Objects.equals(view, fightView);
		fightView = view;
		afterFight = again;
		return changed;
	}

	/**
	 * After an emote step whose items came out of its STASH unit, they go back before moving on; the reminder
	 * outlives the step and ends on the deposit line, or 15 tiles away once the player has been back by the unit (the
	 * Agility Pyramid's emote is done on top, its unit stands at the foot).
	 */
	private void checkDeposit(WorldPoint tile)
	{
		boolean deposited = actWatcher.isDone(SubStep.Done.STASH_DEPOSITED, depositSince);
		boolean near = tile != null && depositAt != null && SubSteps.near(tile, depositAt, DEPOSIT_TILES);
		depositBeenNear |= near;
		if (depositAt != null && tile != null && (deposited || depositBeenNear && !near))
		{
			depositAt = null;
		}
	}

	private void noteDeposit(Step ended, Step next)
	{
		boolean emote = ended != null && ClueTypes.EMOTE.equals(ended.getType()) && ended.getData() != null
			&& ended.getData().getStash() != null;
		boolean takenFromStash = actWatcher.holdsStashItems(stepSince);
		if (!emote || !takenFromStash || next != null && ended.getKey().equals(next.getKey()))
		{
			return;
		}
		RequiredItems.Stash stash = stepNeeds.of(ended).getStash();
		List<String> back = itemsToPutBack(ended);
		if ((stash == RequiredItems.Stash.FILLED || stash == RequiredItems.Stash.EMPTY) && !back.isEmpty())
		{
			depositItems = back;
			depositAt = ended.getData().getStash().getTile();
			depositSince = client.getTickCount();
			depositBeenNear = false;
			depositStashId = ended.getRequirements() == null ? NO_STASH : ended.getRequirements().getStashObjectId();
		}
	}

	/** The ended emote's items still on the player, by the names of the ones carried; none once all are put back. */
	private List<String> itemsToPutBack(Step ended)
	{
		if (ended.getRequirements() == null)
		{
			return List.of();
		}
		Set<Integer> carried = carriedItemIds();
		List<String> back = new ArrayList<>();
		for (ItemRequirement need : ended.getRequirements().getItems())
		{
			carried.stream().filter(need::fulfilledBy).findFirst().ifPresent(id -> back.add(client.getItemDefinition(id).getName()));
		}
		return List.copyOf(back);
	}

	private Set<Integer> carriedItemIds()
	{
		Set<Integer> ids = containerIds(InventoryID.INV);
		ids.addAll(containerIds(InventoryID.WORN));
		return ids;
	}

	private List<SubStep> withDeposit(List<SubStep> base)
	{
		WorldPoint tile = player.getPosition().getTile();
		if (depositAt == null || tile == null)
		{
			return base;
		}
		List<SubStep> all = new ArrayList<>();
		all.add(SubStep.builder().kind(SubStep.Kind.WALK).text("Walk to the STASH unit").waypoint(depositAt).goal(depositAt)
			.radius(SubSteps.ARRIVE_TILES).done(SubStep.Done.STASH_DEPOSITED).build());
		all.add(SubStep.builder().kind(SubStep.Kind.ACT).text("Search the STASH unit: put back the " + String.join(" and ", depositItems))
			.detail("Before moving on").done(SubStep.Done.STASH_DEPOSITED).build());
		all.addAll(base);
		return List.copyOf(all);
	}

	/** The step's items wait in its filled STASH unit, and the player has none of them on. */
	private boolean takeFromStash(Step step)
	{
		if (step == null || step.getData() == null || step.getData().getStash() == null || step.getRequirements() == null)
		{
			return false;
		}
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		List<Item> carried = new ArrayList<>();
		for (ItemContainer container : new ItemContainer[]{worn, inventory})
		{
			if (container != null)
			{
				carried.addAll(List.of(container.getItems()));
			}
		}
		return stepNeeds.of(step).getStash() == RequiredItems.Stash.FILLED
			&& !step.getRequirements().itemsFulfilledBy(carried.toArray(new Item[0]));
	}

	private boolean equipped(Step step)
	{
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		return step != null && step.getRequirements() != null && worn != null && step.getRequirements().itemsFulfilledBy(worn.getItems());
	}

	/** The exit a start leg leaves the planned scene by, or null for any other walk. */
	private Exit exitOf(Leg leg)
	{
		PlanStart start = routes.getStart();
		WalkData.Loaded walk = routes.getWalk();
		if (start == null || walk == null || start.getKind() != PlanStart.Kind.SCENE || !leg.getFrom().equals(start.getTile())
			|| start.getScene().getScene().contains(leg.getTo()))
		{
			return null;
		}
		SceneBfs.ExitReach reach = start.getScene().exitTo(leg.getTo());
		return reach == null ? null : walk.getTable().exit(reach.getOrigin(), reach.getDestination());
	}

	private boolean advance(WorldPoint tile)
	{
		boolean moved = false;
		while (index < steps.size() && isComplete(steps.get(index), tile))
		{
			index++;
			jumpedThisSubStep = false;
			moved = true;
		}
		SubStep current = index < steps.size() ? steps.get(index) : null;
		if (current != null && current.getKind() == SubStep.Kind.ACT && routeStep != null)
		{
			actReachedStepKey = actKey(routeStep);
		}
		return moved;
	}

	private boolean stillAtAct(Step step, WorldPoint tile)
	{
		return actKey(step).equals(actReachedStepKey) && tile != null
			&& step.getTargets().stream().anyMatch(target -> target.distanceTo2D(tile) <= STAY_AT_ACT_TILES);
	}

	/**
	 * Arrived where the planner can't see it: the step's NPC is in the scene a short walk away (Brimstail inside his
	 * cave, where core's tile is the entrance), or the player is beside a cache tile of the step core doesn't give. A
	 * step of several places or a probe trip never arrives this way: their NPCs and tiles belong to one stage.
	 */
	private boolean arrived(Step step)
	{
		PlanStart start = routes.getStart();
		SceneBfs.Result scene = start == null ? null : start.getScene();
		boolean parts = step.getStages().stream().allMatch(stage -> stage.getKind() == Stage.Kind.PART);
		if (scene == null || step.getWaypointAct() != null || !parts || ClueTypes.HOT_COLD.equals(step.getType()))
		{
			return false;
		}
		for (WorldPoint other : step.getOtherTiles())
		{
			if (scene.getScene().contains(other) && scene.costNear(other) <= ARRIVED_WALK_TILES)
			{
				return true;
			}
		}
		if (step.getNpcs() == null || step.getNpcs().isEmpty() || client.getTopLevelWorldView() == null)
		{
			return false;
		}
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			WorldPoint at = npc.getWorldLocation();
			if (npc.getName() != null && step.getNpcs().contains(npc.getName()) && scene.getScene().contains(at)
				&& scene.costNear(at) <= ARRIVED_WALK_TILES)
			{
				return true;
			}
		}
		return false;
	}

	private List<SubStep> actsHere(Step step, WorldPoint tile)
	{
		SubStep stageAct = actHere(step, tile);
		return stageAct.getStage() != null ? SubSteps.stageActs(stageAct.getStage()) : Acts.of(step, takeFromStash(step));
	}

	/** At a step of several places, the act of the stage nearest to the player. */
	private static SubStep actHere(Step step, WorldPoint tile)
	{
		Stage nearest = null;
		int nearestDistance = Integer.MAX_VALUE;
		for (Stage stage : step.getStages())
		{
			for (WorldPoint at : stage.getTiles())
			{
				int distance = tile == null || at.getPlane() != tile.getPlane() ? Integer.MAX_VALUE - 1 : at.distanceTo2D(tile);
				if (distance < nearestDistance)
				{
					nearest = stage;
					nearestDistance = distance;
				}
			}
		}
		return nearest == null || nearest.getKind() == Stage.Kind.FINAL ? SubSteps.act(step) : SubSteps.stageAct(nearest);
	}

	/** Each probe tile of a hot/cold step, and each stage left of a step of several places, is an act of its own. */
	private static String actKey(Step step)
	{
		return step.getKey() + "@" + step.getTargets();
	}

	/** The box shows the acts alone: nothing to travel. */
	public boolean isAtStep()
	{
		return !steps.isEmpty() && steps.stream().allMatch(sub -> sub.getKind() == SubStep.Kind.ACT);
	}

	private void clear()
	{
		route = null;
		routeStep = null;
		actReachedStepKey = null;
		steps = List.of();
		index = 0;
		waiting = null;
		refusalCandidate = null;
		highlightItems = Set.of();
		bankHighlightItems = Set.of();
		highlightSpellWidget = -1;
		highlightMenuWidget = ScrollBook.NO_WIDGET;
		highlightTab = Tab.NONE;
		menuTarget = MenuTarget.NONE;
		destinationSetFor = null;
		highlightObjects = List.of();
		fillItems = Set.of();
		fillObjects = List.of();
		fillNpcs = List.of();
	}

	/** A teleport or book change is animating: its item, spell and tab are already used. */
	public boolean isTravelling()
	{
		return isUnderWay();
	}

	private boolean isUnderWay()
	{
		SubStep current = getCurrent();
		Player local = client.getLocalPlayer();
		boolean acting = current != null && (current.isTravel() || current.getChange() != null);
		return acting && local != null && local.getAnimation() != NO_ANIMATION;
	}

	/** A walk to NPCs ends once one of them is rendered close by (an elf in Prifddinas), not at the planned tile. */
	private boolean stageNpcNearby(SubStep step, WorldPoint tile)
	{
		return !step.getNpcIds().isEmpty() && tile != null && highlightNpcs.stream()
			.anyMatch(npc -> npc.getWorldLocation().getPlane() == tile.getPlane()
				&& npc.getWorldLocation().distanceTo2D(tile) <= SubSteps.AREA_ARRIVE_TILES);
	}

	private boolean isComplete(SubStep step, WorldPoint tile)
	{
		switch (step.getKind())
		{
			case WALK:
			case WALK_TO_CLUE:
				return step.getDone() == SubStep.Done.STASH_DEPOSITED && depositAt == null || SubSteps.near(tile, step.getGoal(), step.getRadius())
					|| step.getSpans().stream().anyMatch(place -> place.spans(tile)) || stageNpcNearby(step, tile);
			case WILDERNESS_EXIT:
				return tile != null && tile.getY() <= step.getGoal().getY();
			case TELEPORT:
			case FAIRY_RING:
			case EXIT:
				// Any floor: Shortest Path puts some landings on the wrong one (the diary cape's Kaleb, Slepe)
				return jumpedThisSubStep && tile != null && tile.distanceTo2D(step.getGoal()) <= step.getRadius();
			case ENTER:
				return step.getWayIn().isInside(tile);
			case SPELLBOOK_SWAP:
			case CHANGE_BOOK:
				return client.getVarbitValue(VarbitID.SPELLBOOK) == step.getBook().ordinal();
			case BANK:
				return bankMemory.isBankOpen();
			case WITHDRAW:
				return !bankMemory.isBankOpen();
			case ACT:
				return isActDone(step);
			default:
				return false;
		}
	}

	private boolean isActDone(SubStep act)
	{
		switch (act.getDone())
		{
			case EQUIPPED:
				return equipped(routeStep);
			case STASH_DEPOSITED:
				return depositAt == null || actWatcher.isDone(act.getDone(), stepSince);
			case STASH_WITHDRAWN:
				return actWatcher.isDone(act.getDone(), stepSince) || !takeFromStash(routeStep);
			case ITEM_CARRIED:
				return carriesItemNamed(act.getCarriedWhenDone());
			case ITEM_GONE:
				return !carriesItemNamed(act.getActItemNamed());
			case WAY_IN_WORN:
				return wearsWayInGear(routeStep);
			case GEAR_WORN:
				return wearsGear(act);
			case ITEM_HELD:
				return act.getHeldWhenDone().stream().anyMatch(carriedItemIds()::contains);
			case SLOTS_EMPTY:
				return act.getEmptied().stream().allMatch(requirement -> requirement.fulfilledBy(wornBySlot()));
			default:
				return actWatcher.isDone(act.getDone(), stepSince);
		}
	}

	private void updateHighlights()
	{
		SubStep current = getCurrent();
		highlightItems = Set.of();
		bankHighlightItems = current == null ? Set.of() : current.getBankItems().stream()
			.map(id -> bankMemory.onlyInBankedKit(id) ? ItemID.HUNTSMANS_KIT : id).collect(Collectors.toUnmodifiableSet());
		highlightSpellWidget = -1;
		highlightMenuWidget = ScrollBook.NO_WIDGET;
		highlightTab = Tab.NONE;
		menuTarget = MenuTarget.NONE;
		highlightObjects = List.of();
		highlightNpcs = List.of();
		fillItems = Set.of();
		fillObjects = List.of();
		fillNpcs = List.of();
		heldUses = List.of();
		oneClickLabel = null;
		objectOption = current == null ? null : current.getOption();
		objectsFoundTick = NEVER;
		if (current == null)
		{
			return;
		}
		if (current.getKind() == SubStep.Kind.ACT)
		{
			highlightTab = current.getTab();
			int stash = stashOf(current);
			if (stash != NO_STASH)
			{
				menuTarget = MenuTarget.builder().objectIds(Set.of(stash)).build();
				objectOption = SEARCH_OPTION;
				return;
			}
			if (current.getDone() == SubStep.Done.EQUIPPED)
			{
				refreshEquipItems();
				return;
			}
		}
		if (current.getKind() == SubStep.Kind.ACT && !current.getActItems().isEmpty())
		{
			highlightItems = current.getActItems();
			boolean equips = current.getDone() == SubStep.Done.WAY_IN_WORN || current.getDone() == SubStep.Done.GEAR_WORN;
			fillItems = equips
				? current.getActItems().stream().filter(this::wearsOnLeftClick).collect(Collectors.toUnmodifiableSet())
				: current.getActItems();
			highlightTab = current.getDone() == SubStep.Done.SLOTS_EMPTY ? Tab.EQUIPMENT : Tab.INVENTORY;
			return;
		}
		String planted = plantedTreeLine(current);
		if (planted != null)
		{
			menuTarget = MenuTarget.builder().objectIds(current.getObjectIds()).path(List.of(TRAVEL_OPTION, TREE_OPTION, planted)).build();
		}
		if (current.getKind() == SubStep.Kind.EXIT)
		{
			Exit exit = current.getExit();
			menuTarget = withDialogue(wayTarget(exit.getObjectId(), exit.getOption()), wayInOf(exit.getOrigin(), exit.getDestination()));
			return;
		}
		if (current.getKind() == SubStep.Kind.ENTER)
		{
			StepIndex.PickUp pickUp = current.getWayIn().getPickUp();
			boolean pickedUp = pickUp != null && current.getText().startsWith(KEEP_GOING);
			menuTarget = pickedUp ? MenuTarget.builder().objectIds(Set.copyOf(pickUp.getObjects())).path(List.of(pickUp.getOption())).build()
				: withDialogue(wayTarget(current.getWayIn().getObject(), current.getWayIn().getOption()), current.getWayIn());
			return;
		}
		if (current.getKind() == SubStep.Kind.SPELLBOOK_SWAP)
		{
			highlightSpellWidget = InterfaceID.MagicSpellbook.SPELLBOOK_SWAP;
			highlightTab = Tab.MAGIC;
		}
		else if (current.getKind() == SubStep.Kind.TELEPORT && isSpell(current.getRow()))
		{
			highlightSpellWidget = spellWidget(current.getRow());
			highlightTab = Tab.MAGIC;
		}
		else if (current.getKind() == SubStep.Kind.TELEPORT && current.getRow().getCategory() == Category.MINIGAME)
		{
			highlightSpellWidget = MINIGAME_SPELLS.getOrDefault(currentBook(), InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_STANDARD);
			highlightTab = Tab.MAGIC;
			menuTarget = MenuTarget.builder().line(MenuTarget.Line.MINIGAME).lineName(SubSteps.minigameName(current.getRow())).build();
		}
		else if (current.getKind() == SubStep.Kind.TELEPORT && current.getRow().isLocated())
		{
			MenuTarget target = houseTarget(current.getRow()).toBuilder().alternatives(sameLanding(current)).build();
			boolean set = current.getRow().getName().equals(destinationSetFor);
			menuTarget = set ? target.toBuilder().path(List.of(TELEPORT_TO_DESTINATION)).build() : target;
		}
		else if (current.getKind() == SubStep.Kind.TELEPORT && current.getActItemNamed() != null)
		{
			highlightTab = Tab.INVENTORY;
		}
		else if (current.getKind() == SubStep.Kind.TELEPORT && current.getRow().getCategory() != Category.MINIGAME)
		{
			CatalogRow row = current.getRow();
			highlightHeld(row);
			highlightMenuWidget = ScrollBook.entry(row.getName());
			MenuTarget.MenuTargetBuilder target = MenuTarget.builder().itemIds(highlightItems).itemNames(names(highlightItems))
				.alternatives(sameLanding(current));
			menuTarget = SubSteps.isQuetzalMap(row)
				? target.path(List.of(SIGNAL_OPTION)).line(MenuTarget.Line.QUETZAL).lineId(SubSteps.quetzalTableId(row)).build()
				: target.path(optionPath(row.getName())).build();
		}
		else if (current.getKind() == SubStep.Kind.FAIRY_RING && current.getRow().getCode() != null)
		{
			menuTarget = MenuTarget.builder().objectIds(FAIRY_RING_OBJECTS).line(MenuTarget.Line.FAIRY_CODE)
				.lineKey(current.getRow().getCode()).build();
		}
		else if (current.getKind() == SubStep.Kind.CHANGE_BOOK && current.getChange().isAnywhere())
		{
			highlightHeld(asRow(current.getChange()));
			menuTarget = MenuTarget.builder().itemIds(highlightItems).itemNames(names(highlightItems))
				.path(List.of(SPELLBOOK_OPTION, bookLabel(current.getBook()))).build();
		}
		else if (current.getKind() == SubStep.Kind.CHANGE_BOOK && current.getChange().isInHouse())
		{
			menuTarget = MenuTarget.builder().objectIds(Set.of(current.getChange().getObjectId()))
				.path(List.of(SPELLBOOK_OPTION, bookLabel(current.getBook()))).build();
		}
		oneClickLabel = directLabel(current);
		fillItems = oneClickItems(oneClickLabel);
	}

	/** None for a quetzal: its option only opens the map. */
	private String directLabel(SubStep current)
	{
		if (menuTarget.getLine() == MenuTarget.Line.QUETZAL)
		{
			return null;
		}
		if (menuTarget.destination() != null)
		{
			return menuTarget.destination();
		}
		List<String> path = current.getRow() == null ? List.of() : optionPath(current.getRow().getName());
		return path.isEmpty() ? null : path.get(path.size() - 1);
	}

	private Set<Integer> oneClickItems(String label)
	{
		boolean signals = menuTarget.getLine() == MenuTarget.Line.QUETZAL;
		boolean oneDestination = label == null && hasOneDestination(getCurrent());
		return heldUses.stream()
			.filter(use -> signals ? SIGNAL_OPTION.equalsIgnoreCase(leftClickOrFirstAction(use))
				: oneDestination ? OneClick.isOwnTeleport(leftClickOrFirstAction(use)) : OneClick.teleportsTo(leftClick(use), label))
			.map(ItemUse::getItemId)
			.collect(Collectors.toUnmodifiableSet());
	}

	private String leftClick(ItemUse use)
	{
		boolean worn = use.getContainer() == Container.WORN;
		String learned = worn ? leftClicks.worn(use.getItemId(), client.getItemDefinition(use.getItemId()).getName())
			: leftClicks.item(use.getItemId());
		if (learned != null)
		{
			return learned;
		}
		return worn ? swapperClicks.worn(use.getItemId()) : swapperClicks.item(use.getItemId());
	}

	/** Before a hover, a carried item no swap applies to left-clicks its first inventory action. */
	private String leftClickOrFirstAction(ItemUse use)
	{
		String known = leftClick(use);
		if (known != null || use.getContainer() == Container.WORN)
		{
			return known;
		}
		for (String action : client.getItemDefinition(use.getItemId()).getInventoryActions())
		{
			if (action != null)
			{
				return action;
			}
		}
		return null;
	}

	/** Walking to the STASH unit the next act searches: it is outlined from the walk on, like a bank. */
	private int stashAhead()
	{
		SubStep current = getCurrent();
		SubStep next = getNext();
		return current != null && current.getKind() == SubStep.Kind.WALK && next != null ? stashOf(next) : NO_STASH;
	}

	private int stashOf(SubStep act)
	{
		if (act.getDone() == SubStep.Done.STASH_DEPOSITED)
		{
			return depositStashId;
		}
		boolean taking = act.getDone() == SubStep.Done.STASH_WITHDRAWN && routeStep != null && routeStep.getRequirements() != null;
		return taking ? routeStep.getRequirements().getStashObjectId() : NO_STASH;
	}

	/** The carried items the emote step wants on, refreshed every tick as they are put on. */
	private boolean refreshBox()
	{
		SubStep act = index < steps.size() ? steps.get(index) : null;
		boolean talk = act != null && act.getKind() == SubStep.Kind.ACT && act.getActItemNamed() == null && routeStep != null
			&& routeStep.getNpcs() != null && act.getText().startsWith(TALK_TO)
			&& routeStep.getNpcs().contains(act.getText().substring(TALK_TO.length()));
		String box = talk ? carriedBox() : null;
		String npc = box == null ? null : act.getText().substring(TALK_TO.length());
		SubStep view = box == null ? null : act.toBuilder().text("Open the " + box + ", then talk to " + npc + " again")
			.actItemNamed(box).build();
		boolean changed = !Objects.equals(view, boxView);
		boxView = view;
		return changed;
	}

	private String carriedBox()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		for (Item item : inventory == null ? new Item[0] : inventory.getItems())
		{
			String name = item.getId() < 0 ? "" : client.getItemDefinition(item.getId()).getName().toLowerCase(Locale.ROOT);
			for (String box : BOXES)
			{
				if (name.startsWith(box))
				{
					return box;
				}
			}
		}
		return null;
	}

	/** The carried item the act names, refreshed every tick as it arrives (the puzzle box, after the talk). */
	/**
	 * The way in's gear as carried: one item that covers every requirement (the Medallion of the Deep) when the player
	 * has it, else per requirement the item they carry.
	 */
	private boolean equipWhatIsCarried()
	{
		SubStep current = getCurrent();
		if (current == null || current.getDone() != SubStep.Done.WAY_IN_WORN || routeStep == null || routeStep.getData() == null)
		{
			return false;
		}
		List<StepIndex.Requires> worn = routeStep.getData().getRequires().stream().filter(StepIndex.Requires::isWear)
			.collect(Collectors.toList());
		Set<Integer> carried = carriedItemIds();
		Integer coversAll = worn.get(0).getIds().stream().filter(carried::contains)
			.filter(id -> worn.stream().allMatch(need -> need.getIds().contains(id))).findFirst().orElse(null);
		List<Integer> chosen = coversAll != null ? List.of(coversAll) : worn.stream()
			.map(need -> need.getIds().stream().filter(carried::contains).findFirst().orElse(need.getIds().get(0)))
			.distinct().collect(Collectors.toList());
		List<String> names = chosen.stream().map(id -> client.getItemDefinition(id).getName()).collect(Collectors.toList());
		return replaceCurrent(current.toBuilder().text(EQUIP_TEXT).items(names).actItems(Set.copyOf(chosen)).build());
	}

	/** The gear a part's act wants, from what is carried: the fewest pieces to put on, listed by name. */
	private boolean equipGear()
	{
		SubStep current = getCurrent();
		if (current == null || current.getDone() != SubStep.Done.GEAR_WORN || routeStep == null)
		{
			return false;
		}
		Set<Integer> worn = containerIds(InventoryID.WORN);
		Set<Integer> inventory = containerIds(InventoryID.INV);
		List<Integer> chosen = wornNeeds(current).stream().flatMap(need -> toEquip(need.getRequirement(), worn, inventory).stream())
			.distinct().collect(Collectors.toList());
		if (chosen.isEmpty())
		{
			return false;
		}
		List<String> names = chosen.stream().map(id -> client.getItemDefinition(id).getName()).collect(Collectors.toList());
		return replaceCurrent(current.toBuilder().text(EQUIP_TEXT).items(names).actItems(Set.copyOf(chosen)).build());
	}

	/** The worn items an act wants off, refreshed as each comes off. */
	private boolean removeGear()
	{
		SubStep current = getCurrent();
		if (current == null || current.getDone() != SubStep.Done.SLOTS_EMPTY)
		{
			return false;
		}
		Item[] worn = wornBySlot();
		Set<Integer> off = Arrays.stream(EquipmentInventorySlot.values())
			.filter(slot -> slot.getSlotIdx() < worn.length && worn[slot.getSlotIdx()].getId() != -1)
			.filter(slot -> current.getEmptied().stream().anyMatch(requirement -> !EmptySlots.allows(requirement, slot,
				worn[slot.getSlotIdx()].getId())))
			.map(slot -> worn[slot.getSlotIdx()].getId())
			.collect(Collectors.toUnmodifiableSet());
		return replaceCurrent(current.toBuilder().actItems(off).build());
	}

	private boolean replaceCurrent(SubStep listed)
	{
		if (listed.equals(getCurrent()))
		{
			return false;
		}
		List<SubStep> updated = new ArrayList<>(steps);
		updated.set(index, listed);
		steps = List.copyOf(updated);
		updateHighlights();
		return true;
	}

	private Item[] wornBySlot()
	{
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		return worn == null ? new Item[0] : worn.getItems();
	}

	private boolean wearsGear(SubStep act)
	{
		Set<Integer> worn = containerIds(InventoryID.WORN);
		return routeStep != null && wornNeeds(act).stream().allMatch(need -> need.getRequirement() instanceof GearRequirement
			? ((GearRequirement) need.getRequirement()).isWorn(worn) : need.getRequirement().fulfilledBy(asItems(worn)));
	}

	private List<RequiredItems.Need> wornNeeds(SubStep act)
	{
		return stepNeeds.of(routeStep).getNeeds().stream().filter(need -> act.getWornNeeds().contains(need.getName()))
			.collect(Collectors.toList());
	}

	private static List<Integer> toEquip(ItemRequirement requirement, Set<Integer> worn, Set<Integer> inventory)
	{
		if (requirement instanceof GearRequirement)
		{
			return ((GearRequirement) requirement).choose(worn, inventory, Set.of()).map(GearPicker.Choice::getEquip).orElse(List.of());
		}
		if (requirement.fulfilledBy(asItems(worn)))
		{
			return List.of();
		}
		List<Integer> fitting = inventory.stream().filter(requirement::fulfilledBy).sorted().collect(Collectors.toList());
		boolean outfit = requirement instanceof AllRequirementsCollection;
		return outfit || fitting.isEmpty() ? fitting : List.of(fitting.get(0));
	}

	private static Item[] asItems(Set<Integer> ids)
	{
		return ids.stream().map(id -> new Item(id, 1)).toArray(Item[]::new);
	}

	private Set<Integer> containerIds(int inventoryId)
	{
		ItemContainer items = client.getItemContainer(inventoryId);
		Set<Integer> ids = new HashSet<>();
		for (Item item : items == null ? new Item[0] : items.getItems())
		{
			if (item.getId() > 0)
			{
				ids.add(item.getId());
			}
		}
		return ids;
	}

	private boolean wearsWayInGear(Step step)
	{
		if (step == null || step.getData() == null)
		{
			return true;
		}
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		Set<Integer> on = new HashSet<>();
		for (Item item : worn == null ? new Item[0] : worn.getItems())
		{
			on.add(item.getId());
		}
		return step.getData().getRequires().stream().filter(StepIndex.Requires::isWear)
			.allMatch(need -> need.getIds().stream().anyMatch(on::contains));
	}

	private boolean carriesItemNamed(String nameStart)
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		for (Item item : inventory == null ? new Item[0] : inventory.getItems())
		{
			if (item.getId() > 0 && client.getItemDefinition(item.getId()).getName().toLowerCase().startsWith(nameStart))
			{
				return true;
			}
		}
		return false;
	}

	private void refreshNamedItems()
	{
		SubStep current = getCurrent();
		boolean clicksItem = current != null && (current.getKind() == SubStep.Kind.ACT || current.getKind() == SubStep.Kind.TELEPORT);
		if (!clicksItem || current.getActItemNamed() == null)
		{
			return;
		}
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		Set<Integer> named = new HashSet<>();
		for (Item item : inventory == null ? new Item[0] : inventory.getItems())
		{
			String name = item.getId() < 0 ? null : client.getItemDefinition(item.getId()).getName();
			if (name != null && name.toLowerCase().startsWith(current.getActItemNamed()))
			{
				named.add(item.getId());
			}
		}
		highlightItems = Set.copyOf(named);
		String option = current.getActOption();
		fillItems = option == null ? highlightItems
			: highlightItems.stream().filter(id -> option.equalsIgnoreCase(itemLeftClick(id))).collect(Collectors.toUnmodifiableSet());
		highlightTab = named.isEmpty() ? highlightTab : Tab.INVENTORY;
		if (option != null)
		{
			takeOutFromBook(current, option);
		}
	}

	/** The book's entry for the scroll, outlined once the book is open, and its Remove option coloured. */
	private void takeOutFromBook(SubStep current, String option)
	{
		boolean fromBook = current.getDone() == SubStep.Done.ITEM_CARRIED && current.getRow() != null;
		Widget book = fromBook ? client.getWidget(InterfaceID.Bookofscrolls.CONTENTS) : null;
		boolean bookOpen = book != null && !book.isHidden();
		highlightMenuWidget = fromBook ? ScrollBook.entry(current.getRow().getName()) : ScrollBook.NO_WIDGET;
		menuTarget = bookOpen
			? MenuTarget.builder().widgetIds(ScrollBook.widgets(current.getRow().getName())).path(List.of(SubSteps.REMOVE_OPTION)).build()
			: MenuTarget.builder().itemIds(highlightItems).itemNames(names(highlightItems)).path(List.of(option)).build();
	}

	/** A skill challenge's task: the items it needs that the player carries, outlined (used on each other, not clicked once). */
	private void refreshTaskItems()
	{
		SubStep current = getCurrent();
		boolean task = current != null && current.getKind() == SubStep.Kind.ACT && current.getStage() != null
			&& current.getStage().getKind() == Stage.Kind.TASK;
		if (!task || routeStep == null || routeStep.getRequirements() == null)
		{
			return;
		}
		Set<Integer> carried = carriedItemIds();
		if (neededFromCarriedKit() != null)
		{
			highlightItems = Set.of(ItemID.HUNTSMANS_KIT);
			highlightTab = Tab.INVENTORY;
			return;
		}
		StepIndex.Cast cast = routeStep.getData() == null ? null : routeStep.getData().getCast();
		if (cast != null && cast.getOn().stream().anyMatch(carried::contains))
		{
			highlightCast(cast, carried);
			return;
		}
		if (current.getStage().getOption() != null && makingNow(carried) == null)
		{
			return;
		}
		StepIndex.Making making = makingNow(carried);
		highlightItems = making != null
			? carried.stream().filter(id -> making.getItems().stream().anyMatch(group -> group.contains(id))).collect(Collectors.toUnmodifiableSet())
			: carried.stream().filter(id -> routeStep.getRequirements().getItems().stream().anyMatch(need -> need.fulfilledBy(id)))
			.collect(Collectors.toUnmodifiableSet());
		fillItems = highlightItems.stream().filter(this::actsOnLeftClick).collect(Collectors.toUnmodifiableSet());
		highlightTab = highlightItems.isEmpty() ? highlightTab : Tab.INVENTORY;
	}

	/** The book to switch to first, else the making step reached; null when the task needs neither. */
	private String taskDetail()
	{
		String book = routeStep == null || routeStep.getData() == null ? null : routeStep.getData().getBook();
		if (book != null && !book.equals(currentBook().name()))
		{
			return "Switch to the " + book.toLowerCase(Locale.ROOT) + " spellbook first";
		}
		String inKit = neededFromCarriedKit();
		if (inKit != null)
		{
			return "Take the " + inKit + " out of the huntsman's kit";
		}
		StepIndex.Making making = makingNow(carriedItemIds());
		return making == null ? null : making.getText();
	}

	/**
	 * At a spirit tree for the planted-tree task: the player's first planted tree, as its line in the tree's list, reached
	 * by Travel or, on the house's spiritual fairy tree, by Tree.
	 */
	private String plantedTreeLine(SubStep current)
	{
		boolean task = current.getKind() == SubStep.Kind.ACT && current.getStage() != null
			&& current.getStage().getKind() == Stage.Kind.TASK;
		PlayerSnapshot snapshot = player.getSnapshot();
		if (!task || routeStep == null || routeStep.getData() == null || !routeStep.getData().isToPlantedTree() || snapshot == null)
		{
			return null;
		}
		return snapshot.getPlantedTrees().stream().sorted().findFirst().orElse(null);
	}

	/** The name of a step item only the carried huntsman's kit holds, or null. */
	private String neededFromCarriedKit()
	{
		Set<Integer> carried = carriedItemIds();
		BankMemory.Seen kit = carried.contains(ItemID.HUNTSMANS_KIT) ? bankMemory.getKit() : null;
		if (kit == null || routeStep == null)
		{
			return null;
		}
		Item[] held = asItems(carried);
		for (RequiredItems.Need need : stepNeeds.of(routeStep).getNeeds())
		{
			if (!need.getRequirement().fulfilledBy(held))
			{
				Integer stored = kit.items.keySet().stream().filter(need.getRequirement()::fulfilledBy).findFirst().orElse(null);
				if (stored != null)
				{
					return client.getItemDefinition(stored).getName().toLowerCase(Locale.ROOT);
				}
			}
		}
		return null;
	}

	/** The spell's button until it is picked (the last of its buttons shown), then the items it is cast on. */
	private void highlightCast(StepIndex.Cast cast, Set<Integer> carried)
	{
		boolean picked = client.isWidgetSelected();
		highlightItems = picked ? carried.stream().filter(cast.getOn()::contains).collect(Collectors.toUnmodifiableSet()) : Set.of();
		fillItems = highlightItems;
		highlightSpellWidget = picked ? -1 : cast.getWidgets().stream().filter(this::widgetShown).reduce((first, last) -> last)
			.orElse(cast.getWidgets().isEmpty() ? -1 : cast.getWidgets().get(0));
		highlightTab = picked ? Tab.INVENTORY : Tab.MAGIC;
	}

	private boolean widgetShown(int widgetId)
	{
		Widget widget = client.getWidget(widgetId);
		return widget != null && !widget.isHidden();
	}

	/** The furthest making step whose worked-on item is carried; null before any is, or for a task made of nothing. */
	private StepIndex.Making makingNow(Set<Integer> carried)
	{
		List<StepIndex.Making> making = routeStep == null || routeStep.getData() == null ? List.of() : routeStep.getData().getMaking();
		StepIndex.Making now = null;
		for (StepIndex.Making step : making)
		{
			if (!step.getItems().isEmpty() && step.getItems().get(0).stream().anyMatch(carried::contains))
			{
				now = step;
			}
		}
		return now;
	}

	/** A task act made in steps says the step it is at under the act. */
	private boolean showMaking()
	{
		SubStep current = getCurrent();
		boolean task = current != null && current.getKind() == SubStep.Kind.ACT && current.getStage() != null
			&& current.getStage().getKind() == Stage.Kind.TASK;
		String detail = task ? taskDetail() : null;
		if (detail == null || detail.equals(current.getDetail()))
		{
			return false;
		}
		List<SubStep> updated = new ArrayList<>(steps);
		updated.set(index, current.toBuilder().detail(detail).build());
		steps = List.copyOf(updated);
		return true;
	}

	private void refreshEquipItems()
	{
		SubStep current = getCurrent();
		if (current == null || current.getKind() != SubStep.Kind.ACT || current.getDone() != SubStep.Done.EQUIPPED
			|| routeStep == null || routeStep.getRequirements() == null)
		{
			return;
		}
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		Set<Integer> wanted = new HashSet<>();
		for (Item item : inventory == null ? new Item[0] : inventory.getItems())
		{
			if (routeStep.getRequirements().getItems().stream().anyMatch(need -> need.fulfilledBy(item.getId())))
			{
				wanted.add(item.getId());
			}
		}
		highlightItems = Set.copyOf(wanted);
		fillItems = wanted.stream().filter(this::wearsOnLeftClick).collect(Collectors.toUnmodifiableSet());
	}

	/** "Green dragon leather x2" while more than one is still to take, else the name alone. */
	private String withdrawLine(int itemId)
	{
		String name = client.getItemDefinition(itemId).getName();
		if (bankMemory.onlyInBankedKit(itemId))
		{
			return client.getItemDefinition(ItemID.HUNTSMANS_KIT).getName() + " (" + name + " inside)";
		}
		int more = stillToTake(itemId);
		return more > 1 ? name + " x" + more : name;
	}

	/** The fewest of the item that, with what is carried, meet every step need it counts towards. */
	private int stillToTake(int itemId)
	{
		Map<Integer, Integer> carried = new HashMap<>();
		for (int container : new int[]{InventoryID.INV, InventoryID.WORN})
		{
			ItemContainer items = client.getItemContainer(container);
			for (Item item : items == null ? new Item[0] : items.getItems())
			{
				if (item.getId() > 0)
				{
					carried.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}
		int most = 0;
		for (RequiredItems.Need need : routeStep == null ? List.<RequiredItems.Need>of() : stepNeeds.of(routeStep).getNeeds())
		{
			ItemRequirement requirement = need.getRequirement();
			if (requirement.fulfilledBy(itemId) && !requirement.fulfilledBy(withMore(carried, itemId, 0)))
			{
				most = Math.max(most, fewestMeeting(requirement, carried, itemId));
			}
		}
		return most;
	}

	/** Doubles, then halves back: needs run from 1 to thousands (coins). 0 when this item alone can't meet it (an outfit). */
	private static int fewestMeeting(ItemRequirement requirement, Map<Integer, Integer> carried, int itemId)
	{
		int high = 1;
		while (high < MAX_STACK && !requirement.fulfilledBy(withMore(carried, itemId, high)))
		{
			high *= 2;
		}
		if (high >= MAX_STACK)
		{
			return 0;
		}
		int low = high / 2;
		while (low + 1 < high)
		{
			int middle = (low + high) / 2;
			if (requirement.fulfilledBy(withMore(carried, itemId, middle)))
			{
				high = middle;
			}
			else
			{
				low = middle;
			}
		}
		return high;
	}

	private static Item[] withMore(Map<Integer, Integer> carried, int itemId, int more)
	{
		Map<Integer, Integer> items = new HashMap<>(carried);
		items.merge(itemId, more, Integer::sum);
		return items.entrySet().stream().map(e -> new Item(e.getKey(), e.getValue())).toArray(Item[]::new);
	}

	/** A task's item does the act on a left click when it is put on or used on another (a tinderbox, a herb). */
	private boolean actsOnLeftClick(int itemId)
	{
		String click = itemLeftClick(itemId);
		return click != null && (WEAR_OPTIONS.contains(click) || USE_OPTION.equals(click));
	}

	private boolean wearsOnLeftClick(int itemId)
	{
		String click = itemLeftClick(itemId);
		return click != null && WEAR_OPTIONS.contains(click);
	}

	private String itemLeftClick(int itemId)
	{
		String learned = leftClicks.item(itemId);
		String click = learned != null ? learned : swapperClicks.item(itemId);
		if (click == null)
		{
			String[] actions = client.getItemDefinition(itemId).getInventoryActions();
			for (int i = 0; actions != null && i < actions.length && click == null; i++)
			{
				click = actions[i];
			}
		}
		return click;
	}

	private List<TileObject> oneClickObjects(List<TileObject> objects)
	{
		if (objectOption != null)
		{
			return objects.stream().filter(object -> objectOption.equalsIgnoreCase(leftClick(object)))
				.collect(Collectors.toUnmodifiableList());
		}
		boolean ring = menuTarget.getLine() == MenuTarget.Line.FAIRY_CODE && menuTarget.getLineKey() != null;
		String firstClick = menuTarget.getPath().isEmpty() ? null : menuTarget.getPath().get(0);
		return objects.stream()
			.filter(object -> ring ? ringGoesTo(leftClick(object), menuTarget.getLineKey())
				: OneClick.teleportsTo(leftClick(object), oneClickLabel) || firstClick != null && MenuLabels.matches(leftClick(object), firstClick))
			.collect(Collectors.toUnmodifiableList());
	}

	private List<NPC> oneClickNpcs(List<NPC> npcs)
	{
		return npcOption == null ? List.of()
			: npcs.stream().filter(npc -> npcOption.equalsIgnoreCase(leftClick(npc))).collect(Collectors.toUnmodifiableList());
	}

	/** Learned from the hover menu, else the NPC's first option. */
	private String leftClick(NPC npc)
	{
		String learned = leftClicks.npc(npc.getId());
		if (learned != null)
		{
			return learned;
		}
		NPCComposition definition = npc.getTransformedComposition();
		String[] actions = definition == null ? null : definition.getActions();
		for (int i = 0; actions != null && i < actions.length; i++)
		{
			if (actions[i] != null)
			{
				return actions[i];
			}
		}
		return null;
	}

	/** Menu Entry Swapper's "Last-destination" carries no code, so the remembered last ring stands in for it. */
	private boolean ringGoesTo(String leftClick, String code)
	{
		if (MenuLabels.isLastDestination(leftClick, code))
		{
			return true;
		}
		PlayerSnapshot snapshot = player.getSnapshot();
		String last = snapshot == null ? null : snapshot.getLastFairyRing();
		return leftClick != null && !leftClick.contains("(") && MenuLabels.isLastDestination(leftClick + " (" + last + ")", code);
	}

	/** Whether a left click on {@code object} is {@code option}, Menu Entry Swapper's swaps included. */
	public boolean isLeftClick(TileObject object, String option)
	{
		String click = leftClick(object);
		return click != null && click.equalsIgnoreCase(option);
	}

	private String leftClick(TileObject object)
	{
		String learned = leftClicks.object(object.getId());
		if (learned != null)
		{
			return learned;
		}
		ObjectComposition definition = client.getObjectDefinition(object.getId());
		String swapped = swapperClicks.object(definition);
		return swapped != null ? swapped : OneClick.defaultClick(definition);
	}

	static MenuTarget houseTarget(CatalogRow row)
	{
		MenuTarget.MenuTargetBuilder target = MenuTarget.builder().objectIds(objectsOf(row));
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
				return target.npcIds(Set.of(NpcID.QUETZAL_CHILD_GREEN)).path(List.of(TRAVEL_OPTION)).line(MenuTarget.Line.QUETZAL)
					.lineId(SubSteps.quetzalTableId(row)).build();
			case "list":
				return target.path(listPath(row, outside, parts[1])).build();
			case "nexus":
			{
				String[] nexus = parts[1].split(" ", 2);
				return target.line(MenuTarget.Line.NEXUS).lineId(Integer.parseInt(nexus[0])).lineKey(nexus[1])
					.lineName(SubSteps.nexusPlace(row.getName())).build();
			}
			case "box":
				return target.line(MenuTarget.Line.BOX).lineId(Integer.parseInt(parts[1])).build();
			case "npc":
				return MenuTarget.builder().npcIds(Set.of(row.getObjectId())).path(List.of(parts[1].split(MENU_STEP))).build();
			default:
				return target.path(List.of(parts[1].split(MENU_STEP))).build();
		}
	}

	private static final String MENU_STEP = " > ";
	private static final String KEEP_GOING = "Keep going: ";
	/** Part way in, the line leads on to where the way ends when that is this close on this floor (the cavern, not the pyramid's top). */
	private static final int PART_WAY_LINE_TILES = 60;
	private static final String EQUIP_TEXT = "Equip:";
	private static final String SCROLL_BOOK_NAME = "master scroll book";
	private static final String SET_DESTINATION = "Set Destination";
	private static final String TELEPORT_TO_DESTINATION = "Teleport to Destination";

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
				&& SubSteps.near(row.getDestination(), landing, SAME_LANDING_TILES))
			{
				same.add(houseTarget(row));
			}
		}
		return List.copyOf(same);
	}

	private static boolean hasOneDestination(SubStep current)
	{
		return current != null && current.getKind() == SubStep.Kind.TELEPORT && current.getRow() != null
			&& optionPath(current.getRow().getName()).isEmpty();
	}

	static List<String> optionPath(String rowName)
	{
		String[] parts = rowName.split(": ");
		return parts.length < 2 ? List.of() : List.of(parts).subList(1, parts.length);
	}

	private Set<String> names(Set<Integer> itemIds)
	{
		return itemIds.stream().map(id -> MenuLabels.normalise(client.getItemDefinition(id).getName()))
			.collect(Collectors.toUnmodifiableSet());
	}

	private static String bookLabel(Book book)
	{
		String name = book.name();
		return name.charAt(0) + name.substring(1).toLowerCase();
	}

	private HouseState house()
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		return snapshot == null ? HouseState.NONE : snapshot.getHouse();
	}

	private Book currentBook()
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		return snapshot == null || snapshot.getSpellbook() == null ? Book.STANDARD : snapshot.getSpellbook().getBook();
	}

	private static boolean isSpell(CatalogRow row)
	{
		return row.getCategory() == Category.SPELL || row.getCategory() == Category.HOME;
	}

	/** Runes are left out: the spell widget covers those. */
	private void highlightHeld(CatalogRow row)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		RuneModel runes = player.getRuneModel();
		if (snapshot == null || runes == null)
		{
			return;
		}
		RowAvailability judged = new AvailabilityEngine(runes, id -> "").judge(row, snapshot, AvailabilityService.nowMinutes());
		List<ItemUse> held = judged.getWitness().stream()
			.filter(use -> use.getContainer() == Container.INVENTORY || use.getContainer() == Container.WORN)
			.filter(use -> !runes.isRune(use.getItemId()))
			.collect(Collectors.toList());
		heldUses = List.copyOf(held);
		highlightItems = held.stream().map(ItemUse::getItemId).collect(Collectors.toUnmodifiableSet());
		boolean highlightWorn = !held.isEmpty() && held.stream().allMatch(use -> use.getContainer() == Container.WORN);
		highlightTab = held.isEmpty() ? Tab.NONE : highlightWorn ? Tab.EQUIPMENT : Tab.INVENTORY;
	}

	private static CatalogRow asRow(SpellbookChange change)
	{
		return CatalogRow.builder().name(change.getName()).requirements(change.getRequirements()).build();
	}

	private int spellWidget(CatalogRow row)
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

	static String spellName(String rowName)
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

	private void notifyListeners()
	{
		listeners.forEach(Runnable::run);
	}
}
