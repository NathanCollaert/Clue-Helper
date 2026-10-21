package com.cluehelper.guide;

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
import com.cluehelper.route.Leg;
import com.cluehelper.route.Plan;
import com.cluehelper.route.Route;
import com.cluehelper.route.RoutePlanner;
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
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Text;

/**
 * Follows the committed route sub-step by sub-step (PLAN §4 item 6, §5.5) and says what the highlights and
 * markers point at. Client thread only.
 * <p>
 * Each plan starts from where the player is, so a new committed route is the rest of the way: the guide
 * takes it from its first sub-step and moves on as each completes. While a teleport or book change is under
 * way (the player is animating), a new route waits: casting spends runes or starts a cooldown, which re-plans
 * from the old tile before the landing.
 */
@Slf4j
@Singleton
public class StepGuide
{
	/** The side tab the current sub-step's highlight sits in. */
	public enum Tab
	{
		NONE, INVENTORY, EQUIPMENT, MAGIC
	}

	/** A single tick's move farther than running carries you: a teleport, a ladder, a boat. */
	static final int JUMP_TILES = 4;
	/** A refused change leaves the book as it was; the next tick shows whether it changed. */
	static final int REFUSAL_CHECK_TICKS = 2;
	/** A teleport animates on its click's tick or within two (B14): three ticks without one means it was refused. */
	static final int TELEPORT_REFUSAL_TICKS = 3;
	/** The spell object's param holding its spellbook widget id (B08). */
	private static final int PARAM_SPELL_WIDGET = 596;
	private static final String CAPE_LIMIT_TEXT = "five times per day";
	private static final int NO_ANIMATION = -1;
	private static final int NEVER = Integer.MIN_VALUE;
	/** Furniture is looked up again this often while a sub-step uses it: it can load a tick after the house scene. */
	private static final int OBJECT_REFRESH_TICKS = 5;
	/** A bank node sits on the walkable tile in front of its booth, chest or banker. */
	private static final int BANK_TILES = 3;
	/** The five spirit tree patches' objects (multilocs on their transmit varbits), and the grown tree they show. */
	private static final Set<Integer> PLANTED_SPIRIT_TREES = Set.of(ObjectID.FARMING_SPIRIT_TREE_PATCH_1,
		ObjectID.FARMING_SPIRIT_TREE_PATCH_2, ObjectID.FARMING_SPIRIT_TREE_PATCH_3, ObjectID.FARMING_SPIRIT_TREE_PATCH_4,
		ObjectID.FARMING_SPIRIT_TREE_PATCH_5, ObjectID.SPIRIT_TREE_FULLYGROWN);
	private static final String TRAVEL_OPTION = "Travel";
	private static final String TREE_OPTION = "Tree";
	private static final String SIGNAL_OPTION = "Signal";
	private static final Set<Integer> FAIRY_RING_OBJECTS = Set.of(ObjectID.FAIRYRING_HOMEHUB, ObjectID.FAIRYRING_MINORHUB,
		ObjectID.POH_FAIRY_RING, ObjectID.POH_SPIRIT_RING);
	private static final String SPELLBOOK_OPTION = "Spellbook";
	/** Catalog landings spread 0–2 tiles (B09): another menu's line within this lands at the same place. */
	private static final int SAME_LANDING_TILES = 3;
	/** Minigame Teleport is a spell on every book (since 2026-03-11): Nathan casts it rather than using the Grouping tab. */
	private static final Map<Book, Integer> MINIGAME_SPELLS = Map.of(
		Book.STANDARD, InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_STANDARD,
		Book.ANCIENT, InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_ANCIENT,
		Book.LUNAR, InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_LUNAR,
		Book.ARCEUUS, InterfaceID.MagicSpellbook.TELEPORT_MINIGAME_ARCEUUS);
	/**
	 * Once the act is reached, it stays while the player is this close to a target on any plane: core's tile can
	 * be on another floor than its NPC (Donovan stands upstairs, his tile is on the ground floor).
	 */
	static final int STAY_AT_ACT_TILES = 20;

	private final Client client;
	private final ClueLink clueLink;
	private final RoutePlanner routes;
	private final PlayerModel player;
	private final BankMemory bankMemory;
	private final MenuLines menuLines;
	private final AvailabilityService availability;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private final Map<String, Integer> spellWidgets = new HashMap<>();

	private Runnable unregister;
	private Route route;
	private Step routeStep;
	/** The step whose act the player has reached; a teleport or other jump clears it. */
	private String arrivedAt;
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
	 * Searches since the target or scene changed that found nothing: a house's furniture appears a tick or two after the
	 * load (B17), so those first ticks are searched every tick, then every {@link #OBJECT_REFRESH_TICKS}.
	 */
	private int emptySearches;
	private String menuHint;
	/** A teleport was clicked: a game message right after it with no animation means the game refused it. */
	private int teleportClickTick = NEVER;
	private String teleportRefusal;
	private int teleportCheckTick = NEVER;
	private WorldPoint teleportClickTile;

	@Inject
	StepGuide(Client client, ClueLink clueLink, RoutePlanner routes, PlayerModel player, BankMemory bankMemory, MenuLines menuLines,
		AvailabilityService availability, SwapperClicks swapperClicks)
	{
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

	/** Runs on the client thread whenever the current sub-step or route changes. */
	public Runnable addListener(Runnable listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	public Route getRoute()
	{
		return route;
	}

	/** Null without a route. */
	public SubStep getCurrent()
	{
		return index < steps.size() ? steps.get(index) : null;
	}

	public SubStep getNext()
	{
		return index + 1 < steps.size() ? steps.get(index + 1) : null;
	}

	/** Opening a bank, or walking to the one about to be opened: its booth, chest or banker is outlined, not the tile. */
	public boolean isBankStop()
	{
		SubStep current = getCurrent();
		SubStep next = getNext();
		return current != null && (current.getKind() == SubStep.Kind.BANK
			|| current.getKind() == SubStep.Kind.WALK && next != null && next.getKind() == SubStep.Kind.BANK);
	}

	public List<SubStep> getSteps()
	{
		return steps;
	}

	/** Item ids to outline in the inventory and equipment for the current sub-step. */
	public Set<Integer> getHighlightItems()
	{
		return highlightItems;
	}

	/** Item ids to outline in the open bank for the withdraw sub-step. */
	public Set<Integer> getBankHighlightItems()
	{
		return bankHighlightItems;
	}

	public Tab getHighlightTab()
	{
		return highlightTab;
	}

	/** The entry to outline inside a teleport interface (the master scroll book), or -1. */
	public int getHighlightMenuWidget()
	{
		return highlightMenuWidget;
	}

	/** What to outline in right-click menus and teleport interfaces for the current sub-step (B16). */
	public MenuTarget getMenuTarget()
	{
		return menuTarget;
	}

	/** The house furniture or the bank booths and chests the current sub-step uses, as loaded in the scene. */
	public List<TileObject> getHighlightObjects()
	{
		return highlightObjects;
	}

	/** The held items whose left click teleports straight to the destination: filled, not only outlined. */
	public Set<Integer> getFillItems()
	{
		return fillItems;
	}

	/** The furniture whose left click teleports straight to the destination. */
	public List<TileObject> getFillObjects()
	{
		return fillObjects;
	}

	/** The bankers of the current bank stop. */
	public List<NPC> getHighlightNpcs()
	{
		return highlightNpcs;
	}

	/** "Press 5: Feldip Hills" while another plugin hides the list line to pick; null otherwise. */
	public String getMenuHint()
	{
		return menuHint;
	}

	/** The highlighted item is worn, so its option is in the equipment tab. */
	public boolean isHighlightWorn()
	{
		return highlightWorn;
	}

	/** The spellbook widget to outline for the current sub-step, or -1. */
	public int getHighlightSpellWidget()
	{
		return highlightSpellWidget;
	}

	/** Every place of ours on the rest of the route, for the world map: landings, rings, altars; never core's target. */
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

	/** From the plugin's PostMenuSort handler, after Menu Entry Swapper's: what a left click on the hovered thing does. */
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

	/** From the plugin's GameTick handler, after the planner's. */
	public void onGameTick()
	{
		WorldPoint tile = player.getPosition().getTile();
		boolean jumped = tile != null && lastTile != null
			&& (tile.getPlane() != lastTile.getPlane() || tile.distanceTo2D(lastTile) > JUMP_TILES);
		lastTile = tile;
		jumpedThisSubStep |= jumped;
		if (jumped)
		{
			arrivedAt = null;
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
		changed |= advance(tile);
		checkRefusal();
		checkTeleportRefusal();
		if (changed)
		{
			updateHighlights();
		}
		refreshObjects();
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
		Set<Integer> ids = menuTarget.getObjectIds();
		Set<Integer> npcIds = menuTarget.getNpcIds();
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

	/** The scene is reloading: what was found in it is gone; once loaded, it is looked up again at once (no blank tick). */
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

	/** A click on the current teleport's item or furniture, for the refusal check below. */
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

	/** MESBOX lines during a book change are the refusal backstop (PLAN §5.4 Consumption, B04). */
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

	/** Still here and not animating a few ticks after the message: the teleport didn't happen. */
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
		Step step = clueLink.getCurrentStep();
		WorldPoint tile = player.getPosition().getTile();
		route = committed;
		routeStep = step;
		steps = committed == null || step == null ? List.of() : stillAtAct(step, tile) ? List.of(SubSteps.act(step))
			: SubSteps.of(committed, step, id -> client.getItemDefinition(id).getName(), house());
		index = 0;
		jumpedThisSubStep = false;
		advance(tile);
		updateHighlights();
		refreshObjects();
		notifyListeners();
	}

	/** Moves past every completed sub-step; returns whether any completed. */
	private boolean advance(WorldPoint tile)
	{
		boolean moved = false;
		while (index < steps.size() && isComplete(steps.get(index), tile))
		{
			index++;
			jumpedThisSubStep = false;
			moved = true;
		}
		SubStep current = getCurrent();
		if (current != null && current.getKind() == SubStep.Kind.ACT && routeStep != null)
		{
			arrivedAt = routeStep.getKey();
		}
		return moved;
	}

	private boolean stillAtAct(Step step, WorldPoint tile)
	{
		return step.getKey().equals(arrivedAt) && tile != null
			&& step.getTargets().stream().anyMatch(target -> target.distanceTo2D(tile) <= STAY_AT_ACT_TILES);
	}

	private void clear()
	{
		route = null;
		routeStep = null;
		arrivedAt = null;
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

	/** A teleport, fairy ring or book change has started and not finished: hold the route it belongs to. */
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
				return jumpedThisSubStep && SubSteps.near(tile, step.getGoal(), step.getRadius());
			case SPELLBOOK_SWAP:
			case CHANGE_BOOK:
				return client.getVarbitValue(VarbitID.SPELLBOOK) == step.getBook().ordinal();
			case BANK:
				return bankMemory.isBankOpen();
			case WITHDRAW:
				return !bankMemory.isBankOpen();
			default:
				return false;
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
		objectsFoundTick = NEVER;
		if (current == null)
		{
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
				? target.path(List.of(SIGNAL_OPTION)).line(MenuTarget.Line.QUETZAL).lineId(SubSteps.quetzalSite(row)).build()
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

	/**
	 * The option that teleports straight there: the path's last label, else the row's ("Jewellery box: Emir's Arena").
	 * None for a quetzal: its option only opens the map.
	 */
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
		return heldUses.stream()
			.filter(use -> OneClick.teleportsTo(leftClick(use), label))
			.map(ItemUse::getItemId)
			.collect(Collectors.toUnmodifiableSet());
	}

	/** What hovering showed, else what Menu Entry Swapper's own swap gives (B17). */
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

	private List<TileObject> oneClickObjects(List<TileObject> objects)
	{
		return objects.stream()
			.filter(object -> OneClick.teleportsTo(leftClick(object), oneClickLabel))
			.collect(Collectors.toUnmodifiableList());
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

	/**
	 * A located row's menu (catalog Menu column): the nexus by struct id and map region, the jewellery box by line index,
	 * a quetzal site by its map icon, a 187 list line or the object's own option by label. Outside the house the
	 * object is the row's own (a spirit tree), and Renu for a quetzal.
	 */
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
					.lineId(SubSteps.quetzalSite(row)).build();
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

	/**
	 * The option that opens a 187 list, then its line: Travel on a spirit tree outside, Tree on the house's (B17: on a
	 * spiritual fairy tree "Ring-configure" opens a list too, and was coloured instead). Other house lists name the line
	 * only.
	 */
	private static List<String> listPath(CatalogRow row, boolean outside, String line)
	{
		if (outside)
		{
			return List.of(TRAVEL_OPTION, line);
		}
		return row.getCategory() == Category.SPIRIT_TREE ? List.of(TREE_OPTION, line) : List.of(line);
	}

	/** The furniture a house row needs, else the row's own object; a planted tree is whichever patch object is there. */
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

	/**
	 * The house's other menus that land where this teleport does, among the rows the player can use now: a respawn
	 * portal goes to the player's own respawn point (B16: it was offered as Lumbridge and landed in Edgeville).
	 */
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

	/** "Max cape: Other Teleports: Feldip Hills" → [Other Teleports, Feldip Hills]: the options below the item. */
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

	/** The held items that let the player use the row, runes left out: the spell widget covers those. */
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

	/** Spell rows are named after the spell, with a destination after a colon or a variant in brackets. */
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
