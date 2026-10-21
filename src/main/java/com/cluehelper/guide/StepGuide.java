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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
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
	private static final Set<String> WEAR_OPTIONS = Set.of("Wear", "Wield", "Equip");

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
	private SubStep fightView;
	/** The STASH unit to put the last emote step's items back into; null once done or walked away from. */
	private WorldPoint depositAt;
	private int depositSince = ActWatcher.NEVER;
	private int depositStashId = NO_STASH;
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
	private boolean highlightWorn;
	private int highlightMenuWidget = ScrollBook.NO_WIDGET;
	private Tab highlightTab = Tab.NONE;
	private MenuTarget menuTarget = MenuTarget.NONE;
	private List<TileObject> highlightObjects = List.of();
	private List<NPC> highlightNpcs = List.of();
	private List<TileObject> fillObjects = List.of();
	private int objectsFoundTick = NEVER;
	/**
	 * A house's furniture appears a tick or two after the load, so empty searches repeat every tick at first, then every
	 * {@link #OBJECT_REFRESH_TICKS}.
	 */
	private int emptySearches;
	private String menuHint;
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
		return index < steps.size() ? steps.get(index) : null;
	}

	public SubStep getNext()
	{
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

	public List<NPC> getHighlightNpcs()
	{
		return highlightNpcs;
	}

	public String getMenuHint()
	{
		return menuHint;
	}

	public boolean isHighlightWorn()
	{
		return highlightWorn;
	}

	public int getHighlightSpellWidget()
	{
		return highlightSpellWidget;
	}

	public Map<WorldPoint, String> getMapWaypoints()
	{
		Map<WorldPoint, String> places = new LinkedHashMap<>();
		Route shown = route;
		Step step = routeStep;
		if (shown == null || step == null)
		{
			return places;
		}
		for (Leg leg : shown.getLegs())
		{
			WorldPoint to = leg.getKind() == Leg.Kind.SPELLBOOK_CHANGE ? leg.getChange().getTile() : leg.getTo();
			if (to != null && leg.getKind() != Leg.Kind.BANK && !step.getTargets().contains(to))
			{
				places.putIfAbsent(to, leg.getKind() == Leg.Kind.WALK || leg.getKind() == Leg.Kind.WILDERNESS_EXIT ? "walk here" : leg.describe());
			}
		}
		if (step.getWaypointAct() != null)
		{
			places.putIfAbsent(step.getTargets().get(0), step.getWaypointAct());
		}
		return places;
	}

	private void onPlan(Plan plan)
	{
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
			fillItems = oneClickItems(oneClickLabel);
			fillObjects = oneClickObjects(highlightObjects);
		}
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
		checkRefusal();
		checkTeleportRefusal();
		if (changed)
		{
			updateHighlights();
		}
		refreshObjects();
		refreshEquipItems();
		changed |= refreshHint();
		if (changed)
		{
			notifyListeners();
		}
	}

	private void refreshObjects()
	{
		SubStep current = getCurrent();
		boolean bank = isBankStop();
		MenuTarget target = current != null && !current.getNpcIds().isEmpty() ? MenuTarget.builder().npcIds(current.getNpcIds()).build()
			: isApproach() ? houseTarget(getNext().getRow()) : menuTarget;
		Set<Integer> ids = target.getObjectIds();
		Set<Integer> npcIds = target.getNpcIds();
		if (ids.isEmpty() && npcIds.isEmpty() && !bank)
		{
			highlightObjects = List.of();
			highlightNpcs = List.of();
			fillObjects = List.of();
			return;
		}
		int tick = client.getTickCount();
		boolean nothingYet = highlightObjects.isEmpty() && highlightNpcs.isEmpty() && emptySearches < OBJECT_REFRESH_TICKS;
		if (objectsFoundTick == NEVER || nothingYet || tick - objectsFoundTick >= OBJECT_REFRESH_TICKS)
		{
			emptySearches = objectsFoundTick == NEVER ? 0 : emptySearches + 1;
			objectsFoundTick = tick;
			highlightObjects = bank ? SceneObjects.banksNear(client, current.getWaypoint(), BANK_TILES) : SceneObjects.find(client, ids);
			highlightNpcs = bank ? SceneObjects.bankersNear(client, current.getWaypoint(), BANK_TILES) : SceneObjects.npcs(client, npcIds);
			fillObjects = bank ? List.of() : oneClickObjects(highlightObjects);
		}
	}

	private boolean refreshHint()
	{
		char key = menuLines.hiddenListHotkey(menuTarget);
		String hint = key == 0 ? null : "Press " + key + ": " + menuTarget.destination();
		boolean changed = !Objects.equals(hint, menuHint);
		menuHint = hint;
		return changed;
	}

	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING || event.getGameState() == GameState.HOPPING)
		{
			highlightObjects = List.of();
			highlightNpcs = List.of();
			fillObjects = List.of();
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
			: SubSteps.of(committed, step, id -> client.getItemDefinition(id).getName(), house(), this::exitOf, takeFromStash(step));
		steps = withDeposit(base);
		index = 0;
		jumpedThisSubStep = false;
		advance(tile);
		updateHighlights();
		refreshObjects();
		notifyListeners();
	}

	private boolean refreshFight()
	{
		SubStep act = index < steps.size() ? steps.get(index) : null;
		SubStep view = null;
		if (act != null && act.isFights())
		{
			NPC enemy = actWatcher.getEnemy();
			if (enemy != null)
			{
				boolean emote = act.getDone() == SubStep.Done.URI_HERE;
				view = SubStep.builder().kind(SubStep.Kind.ACT).text("Kill the " + (enemy.getName() == null ? "enemy" : enemy.getName()))
					.detail(emote ? "Stay equipped, then perform the emote again" : "Then dig again").build();
			}
			else if (actWatcher.foughtSince(stepSince))
			{
				view = act.toBuilder().text(act.getAgain()).build();
			}
		}
		boolean changed = !Objects.equals(view, fightView);
		fightView = view;
		return changed;
	}

	/**
	 * After an emote step whose STASH unit is built, the items go back before moving on (Nathan's rule); the reminder
	 * outlives the step and ends on the deposit line or 15 tiles away.
	 */
	private void checkDeposit(WorldPoint tile)
	{
		boolean deposited = actWatcher.isDone(SubStep.Done.STASH_DEPOSITED, depositSince);
		if (depositAt != null && tile != null && (deposited || !SubSteps.near(tile, depositAt, DEPOSIT_TILES)))
		{
			depositAt = null;
		}
	}

	private void noteDeposit(Step ended, Step next)
	{
		boolean emote = ended != null && ClueTypes.EMOTE.equals(ended.getType()) && ended.getData() != null
			&& ended.getData().getStash() != null;
		if (!emote || next != null && ended.getKey().equals(next.getKey()))
		{
			return;
		}
		RequiredItems.Stash stash = stepNeeds.of(ended).getStash();
		if (stash == RequiredItems.Stash.FILLED || stash == RequiredItems.Stash.EMPTY)
		{
			depositAt = ended.getData().getStash().getTile();
			depositSince = client.getTickCount();
			depositStashId = ended.getRequirements() == null ? NO_STASH : ended.getRequirements().getStashObjectId();
		}
	}

	private List<SubStep> withDeposit(List<SubStep> base)
	{
		WorldPoint tile = player.getPosition().getTile();
		if (depositAt == null || tile == null || !SubSteps.near(tile, depositAt, DEPOSIT_TILES))
		{
			return base;
		}
		List<SubStep> all = new ArrayList<>();
		all.add(SubStep.builder().kind(SubStep.Kind.ACT).text("Search the STASH unit: put the items back")
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
		if (scene == null || step.getWaypointAct() != null || !step.getStages().isEmpty() || ClueTypes.HOT_COLD.equals(step.getType()))
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
		return stageAct.getStage() != null ? List.of(stageAct) : Acts.of(step, takeFromStash(step));
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

	/** The panel shows the acts alone: nothing to travel. */
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
		highlightObjects = List.of();
		fillItems = Set.of();
		fillObjects = List.of();
		menuHint = null;
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

	private boolean isComplete(SubStep step, WorldPoint tile)
	{
		switch (step.getKind())
		{
			case WALK:
			case WALK_TO_CLUE:
				return SubSteps.near(tile, step.getGoal(), step.getRadius());
			case WILDERNESS_EXIT:
				return tile != null && tile.getY() <= step.getGoal().getY();
			case TELEPORT:
			case FAIRY_RING:
			case EXIT:
				return jumpedThisSubStep && SubSteps.near(tile, step.getGoal(), step.getRadius());
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
			default:
				return actWatcher.isDone(act.getDone(), stepSince);
		}
	}

	private void updateHighlights()
	{
		SubStep current = getCurrent();
		highlightItems = Set.of();
		bankHighlightItems = current == null ? Set.of() : current.getBankItems();
		highlightSpellWidget = -1;
		highlightWorn = false;
		highlightMenuWidget = ScrollBook.NO_WIDGET;
		highlightTab = Tab.NONE;
		menuTarget = MenuTarget.NONE;
		highlightObjects = List.of();
		highlightNpcs = List.of();
		fillItems = Set.of();
		fillObjects = List.of();
		heldUses = List.of();
		oneClickLabel = null;
		objectOption = null;
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
			fillItems = current.getActItems();
			highlightTab = Tab.INVENTORY;
			return;
		}
		if (current.getKind() == SubStep.Kind.EXIT)
		{
			Exit exit = current.getExit();
			menuTarget = withDialogue(wayTarget(exit.getObjectId(), exit.getOption()), wayInOf(exit.getOrigin(), exit.getDestination()));
			return;
		}
		if (current.getKind() == SubStep.Kind.ENTER)
		{
			menuTarget = withDialogue(wayTarget(current.getWayIn().getObject(), current.getWayIn().getOption()), current.getWayIn());
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
			menuTarget = houseTarget(current.getRow()).toBuilder().alternatives(sameLanding(current)).build();
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
		boolean oneDestination = label == null && hasOneDestination(getCurrent());
		return heldUses.stream()
			.filter(use -> oneDestination ? OneClick.isOwnTeleport(leftClickOrFirstAction(use)) : OneClick.teleportsTo(leftClick(use), label))
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

	private boolean wearsOnLeftClick(int itemId)
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
		return click != null && WEAR_OPTIONS.contains(click);
	}

	private List<TileObject> oneClickObjects(List<TileObject> objects)
	{
		if (objectOption != null)
		{
			return objects.stream().filter(object -> objectOption.equalsIgnoreCase(leftClick(object)))
				.collect(Collectors.toUnmodifiableList());
		}
		boolean ring = menuTarget.getLine() == MenuTarget.Line.FAIRY_CODE && menuTarget.getLineKey() != null;
		return objects.stream()
			.filter(object -> ring ? ringGoesTo(leftClick(object), menuTarget.getLineKey())
				: OneClick.teleportsTo(leftClick(object), oneClickLabel))
			.collect(Collectors.toUnmodifiableList());
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
			default:
				return target.path(List.of(parts[1])).build();
		}
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
		highlightWorn = !held.isEmpty() && held.stream().allMatch(use -> use.getContainer() == Container.WORN);
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
