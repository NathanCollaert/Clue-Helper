package com.cluehelper.guide;

import com.cluehelper.link.ClueLink;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.memory.StashMemory;
import com.cluehelper.player.Container;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.PositionRules;
import com.cluehelper.route.Plan;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.StepNeeds;
import com.cluehelper.route.Route;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.route.WalkCosts;
import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.Stage;
import com.cluehelper.step.StepIndex;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepRequirements;
import com.cluehelper.transport.Category;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.WorldView;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
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

	static final String KEEP_GOING = "Keep going: ";

	private static final String CAPE_LIMIT_TEXT = "five times per day";
	private static final int NO_ANIMATION = -1;
	/** A teleport's animation can end a tick or two before the player lands. */
	private static final int LANDING_TICKS = 3;
	/** A teleport animates on its click's tick or within two: any other animation (a block while being hit) isn't travel. */
	private static final int TRAVEL_START_TICKS = 2;
	/** The Grouping teleport starts its animation 3-6 ticks after the click (8 of 8 in the step campaign). */
	private static final int MINIGAME_TRAVEL_START_TICKS = 7;
	private static final int OBJECT_REFRESH_TICKS = 5;
	/** A bank node sits on the walkable tile in front of its booth, chest or banker. */
	private static final int BANK_TILES = 3;
	/** How far from its boarding tile a ride's NPC may stand. */
	private static final int RIDE_NPC_TILES = 8;
	/**
	 * Core's tile can be on another floor than its NPC (Donovan stands upstairs, his tile is on the ground floor), so a
	 * reached act stays while the player is this close to a target on any plane.
	 */
	private static final int STAY_AT_ACT_TILES = 20;
	/** In scene-walk tiles: 20 ticks of running. */
	private static final int ARRIVED_WALK_TILES = 40;
	/** Part way in, the line leads on to where the way ends when that is this close on this floor (the cavern, not the pyramid's top). */
	private static final int PART_WAY_LINE_TILES = 60;
	private static final String SET_DESTINATION = "Set Destination";
	private static final String ATTACK = "Attack";
	/** The fixed, resizable classic and resizable modern layouts, whose stones open the side panel tabs. */
	private static final Set<Integer> TOPLEVELS = Set.of(InterfaceID.TOPLEVEL, InterfaceID.TOPLEVEL_OSRS_STRETCH,
		InterfaceID.TOPLEVEL_PRE_EOC);

	private final Client client;
	private final RoutePlanner routes;
	private final PlayerModel player;
	private final BankMemory bankMemory;
	private final MenuLines menuLines;
	private final RowTargets rowTargets;
	private final GearActs gearActs;
	private final TaskActs taskActs;
	private final StepHighlights stepHighlights;
	private final ActWatcher actWatcher;
	private final StepNeeds stepNeeds;
	private final SubSteps subSteps;
	private final SceneObjects sceneObjects;
	private final ClueLink clueLink;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private static final String TALK_TO = "Talk to ";
	private static final List<String> BOXES = List.of("puzzle box", "light box");
	private final LeftClicks leftClicks;
	private final DepositReminder deposit;
	private final ChangeRefusal changeRefusal = new ChangeRefusal();
	private final TeleportRefusal teleportRefusal = new TeleportRefusal();
	private final ObeliskDestination obelisk;

	private Runnable unregister;
	private Route route;
	private Step routeStep;
	/** When the guided step began: an act's event counts from then. */
	private int stepSince = ActWatcher.NEVER;
	private String actReachedStepKey;
	private List<SubStep> steps = List.of();
	private int index;
	private boolean jumpedThisSubStep;
	private int teleportAnimatedTick = ActWatcher.NEVER;
	private int travelClickTick = ActWatcher.NEVER;
	/** The travel sub-step's own animation is playing: one that started right after a click during it. */
	private boolean travelStarted;
	/**
	 * The fairy ring sub-step's code took the player away: the leaving animation played during it. The last ring's
	 * arrival animation is still playing when the next code is dialled, so any animation won't do.
	 */
	private boolean ringVanished;
	private WorldPoint lastTile;
	/** A route that came while a teleport was under way, followed once it lands. */
	private Waiting waiting;
	/** While an enemy attacks during a dig or an emote. */
	private Fight fight;
	private RingChain ringChain;
	/** After a talk that hands over a puzzle or light box: open it (core solves it), then talk again. */
	private SubStep boxView;
	/** Ground items to pick up now, by tile. */
	private List<WorldPoint> pickUpTiles = List.of();
	private Highlights highlights = Highlights.NONE;
	private SceneHighlights scene = SceneHighlights.NONE;

	@Inject
	StepGuide(Client client, RoutePlanner routes, PlayerModel player, BankMemory bankMemory, MenuLines menuLines,
		RowTargets rowTargets, LeftClicks leftClicks, GearActs gearActs, TaskActs taskActs, StepHighlights stepHighlights,
		DepositReminder deposit, ObeliskDestination obelisk, ActWatcher actWatcher, StepNeeds stepNeeds, SubSteps subSteps,
		SceneObjects sceneObjects, ClueLink clueLink)
	{
		this.clueLink = clueLink;
		this.gearActs = gearActs;
		this.taskActs = taskActs;
		this.stepHighlights = stepHighlights;
		this.subSteps = subSteps;
		this.sceneObjects = sceneObjects;
		this.actWatcher = actWatcher;
		this.deposit = deposit;
		this.obelisk = obelisk;
		this.stepNeeds = stepNeeds;
		this.leftClicks = leftClicks;
		this.menuLines = menuLines;
		this.rowTargets = rowTargets;
		this.bankMemory = bankMemory;
		this.client = client;
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
		if (fight != null)
		{
			return fight.getView();
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
		if (fight != null && fight.getAgain() != null)
		{
			return fight.getAgain();
		}
		return index + 1 < steps.size() ? steps.get(index + 1) : null;
	}

	/**
	 * What the box asks for. Walking to a bank and opening it aren't shown: what to do in it is, from the walk on, while
	 * the line and outline still lead to the bank.
	 */
	public SubStep getShown()
	{
		if (fight != null || boxView != null || index >= steps.size())
		{
			return getCurrent();
		}
		return shownAt(index);
	}

	/** The sub-step after the shown one, past a walk to a bank and its opening as well. */
	public SubStep getShownNext()
	{
		if (fight != null || boxView != null || index >= steps.size())
		{
			return getNext();
		}
		int next = shownIndex(index) + 1;
		return next < steps.size() ? shownAt(next) : null;
	}

	private SubStep shownAt(int at)
	{
		int shownIndex = shownIndex(at);
		SubStep shown = steps.get(shownIndex);
		if (shownIndex == at)
		{
			return shown;
		}
		String bankDetail = steps.get(shownIndex - 1).getDetail();
		return shown.getDetail() == null && bankDetail != null ? shown.toBuilder().detail(bankDetail).build() : shown;
	}

	/**
	 * For a walk to a bank or its opening, the sub-step done in that bank; else {@code at}. A bank opened only to close
	 * it again has nothing to show, so its walk and opening are shown as they are.
	 */
	private int shownIndex(int at)
	{
		SubStep step = steps.get(at);
		boolean walkToBank = step.getKind() == SubStep.Kind.WALK && at + 1 < steps.size()
			&& steps.get(at + 1).getKind() == SubStep.Kind.BANK;
		int bank = step.getKind() == SubStep.Kind.BANK ? at : walkToBank ? at + 1 : -1;
		if (bank < 0 || bank + 1 >= steps.size())
		{
			return at;
		}
		SubStep task = steps.get(bank + 1);
		boolean onlyCloses = task.getKind() == SubStep.Kind.WITHDRAW && task.getItems().isEmpty();
		return onlyCloses ? at : bank + 1;
	}

	/** At the bank, or walking to it: acts done on the spot first (a scroll taken out of its book) still end there. */
	private boolean isBankStop()
	{
		SubStep current = getCurrent();
		if (current == null || current.getKind() == SubStep.Kind.BANK)
		{
			return current != null;
		}
		if (current.getKind() != SubStep.Kind.WALK || fight != null && fight.getAgain() != null)
		{
			return false;
		}
		for (int i = index + 1; i < steps.size(); i++)
		{
			if (steps.get(i).getKind() != SubStep.Kind.ACT)
			{
				return steps.get(i).getKind() == SubStep.Kind.BANK;
			}
		}
		return false;
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

	public List<SubStep> getSteps()
	{
		return steps;
	}

	public Set<Integer> getHighlightItems()
	{
		return highlights.getItems();
	}

	public Set<Integer> getBankHighlightItems()
	{
		return highlights.getBankItems();
	}

	public Tab getHighlightTab()
	{
		return highlights.getTab();
	}

	public int getHighlightMenuWidget()
	{
		return highlights.getMenuWidget();
	}

	public MenuTarget getMenuTarget()
	{
		return highlights.getMenuTarget();
	}

	public MenuTarget getOptionTarget()
	{
		return highlights.getOptionTarget();
	}

	public List<TileObject> getHighlightObjects()
	{
		return scene.getObjects();
	}

	public Set<Integer> getFillItems()
	{
		return highlights.getFillItems();
	}

	public List<TileObject> getFillObjects()
	{
		return scene.getFillObjects();
	}

	public List<NPC> getFillNpcs()
	{
		return onlyFought(scene.getFillNpcs());
	}

	public List<NPC> getHighlightNpcs()
	{
		return onlyFought(scene.getNpcs());
	}

	/** While the player fights one of them (the dust devil to slay), the others are no longer the way on. */
	private List<NPC> onlyFought(List<NPC> npcs)
	{
		Player self = client.getLocalPlayer();
		Actor target = self == null ? null : self.getInteracting();
		NPC fought = target instanceof NPC && npcs.contains(target) ? (NPC) target
			: npcs.stream().filter(npc -> self != null && npc.getInteracting() == self).findFirst().orElse(null);
		return fought == null ? npcs : List.of(fought);
	}

	public int getHighlightSpellWidget()
	{
		return highlights.getSpellWidget();
	}

	/** An interface option the task picks and its confirm button, outlined while the interface is open. */
	public List<Widget> getHighlightChoice()
	{
		return taskActs.choice(getCurrent(), routeStep);
	}

	private void onPlan(Plan plan)
	{
		PlanStart start = routes.getStart();
		Step step = routes.travelStep();
		boolean noWalk = start != null && start.getKind() == PlanStart.Kind.TELEPORT_ONLY;
		boolean partWay = start != null && step != null && step.getData().getEntry() != null
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
		if (isTravelling())
		{
			waiting = new Waiting(committed, player.getPosition().getTile());
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
			highlights = stepHighlights.withItemFill(highlights, getCurrent(), routeStep);
			scene = scene.toBuilder().fillObjects(stepHighlights.fillObjects(scene.getObjects(), highlights))
				.fillNpcs(stepHighlights.fillNpcs(scene.getNpcs(), scene.getNpcOption())).build();
		}
	}

	/** Call from a GameTick handler that runs after the planner's. */
	public void onGameTick()
	{
		WorldPoint tile = player.getPosition().getTile();
		boolean jumped = PositionRules.teleported(lastTile, tile);
		lastTile = tile;
		jumpedThisSubStep |= jumped;
		if (jumped)
		{
			actReachedStepKey = null;
		}
		boolean clickedJustNow = travelClickTick != ActWatcher.NEVER && client.getTickCount() - travelClickTick <= travelStartTicks();
		travelStarted = isTravelStep() && isAnimating() && (travelStarted || clickedJustNow);
		if (travelStarted && getCurrent().isTravel())
		{
			teleportAnimatedTick = client.getTickCount();
		}
		ringVanished |= isRingStep() && isVanishing();
		// Before the early returns below: a follow on every tick near the step would starve them (a deposit never seen)
		actWatcher.onGameTick(routeStep);
		deposit.onGameTick(tile);
		boolean changed = false;
		if (waiting != null && !isTravelling())
		{
			Waiting next = waiting;
			boolean stale = PositionRules.teleported(next.getFrom(), tile);
			waiting = null;
			if (!stale)
			{
				follow(next.getRoute());
				return;
			}
		}
		if (routeStep != null && !isAtStep() && !itemsFirst(route, routeStep) && arrived(routeStep))
		{
			follow(route);
			return;
		}
		changed |= advance(tile);
		changed |= refreshFight();
		changed |= refreshBox();
		changed |= watchDestinationSet();
		changed |= keyFromCharterList();
		changed |= partWayIn(tile);
		changed |= pickUpForAct(tile);
		changed |= refreshGear();
		changed |= showMaking();
		checkRefusal();
		checkTeleportRefusal();
		show(changed);
	}

	/** The bank sub-step ends the frame the bank opens, not a tick later, so the withdraw guidance shows with the bank. */
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			show(advance(lastTile));
		}
	}

	private void show(boolean changed)
	{
		if (changed)
		{
			updateHighlights();
		}
		refreshObjects();
		highlights = stepHighlights.refreshed(highlights, getCurrent(), routeStep);
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
		MenuTarget target = outlineTarget(current, option);
		boolean ownOption = !option.isEmpty() && highlights.getMenuTarget() == MenuTarget.NONE;
		highlights = highlights.toBuilder().optionTarget(ownOption ? target : MenuTarget.NONE).build();
		boolean nothingToOutline = target.getObjectIds().isEmpty() && target.getNpcIds().isEmpty() && target.getNpcNames().isEmpty();
		if (nothingToOutline && !bank || isCoresOwnObject(current))
		{
			scene = scene.toBuilder().objects(List.of()).npcs(List.of()).fillObjects(List.of()).fillNpcs(List.of()).build();
			return;
		}
		int tick = client.getTickCount();
		boolean nothingYet = scene.isEmpty() && scene.getEmptySearches() < OBJECT_REFRESH_TICKS;
		if (scene.getFoundTick() == ActWatcher.NEVER || nothingYet || tick - scene.getFoundTick() >= OBJECT_REFRESH_TICKS)
		{
			scene = searchScene(current, target, bank, tick);
		}
	}

	/** An act on the clue's own object (the spirit tree), which core marks already: only its option is coloured. */
	private boolean isCoresOwnObject(SubStep current)
	{
		return current != null && current.getKind() == SubStep.Kind.ACT && !current.getObjectIds().isEmpty()
			&& routeStep != null && routeStep.getObjectIds().containsAll(current.getObjectIds());
	}

	/**
	 * What the scene is searched for: the sub-step's own NPCs or objects with its option, the furniture a walk leads to,
	 * the STASH unit the next act searches, else the menu target's objects.
	 */
	private MenuTarget outlineTarget(SubStep current, List<String> option)
	{
		if (current != null && !current.getNpcIds().isEmpty())
		{
			return MenuTarget.builder().npcIds(current.getNpcIds()).path(option).build();
		}
		if (current != null && !current.getObjectIds().isEmpty())
		{
			return MenuTarget.builder().objectIds(current.getObjectIds()).path(option).build();
		}
		if (current != null && !current.getNpcNames().isEmpty())
		{
			return MenuTarget.builder().npcNames(current.getNpcNames()).path(option).build();
		}
		if (isApproach())
		{
			return rowTargets.located(getNext().getRow());
		}
		int stash = stashAhead();
		return stash != StepRequirements.NO_STASH ? MenuTarget.builder().objectIds(Set.of(stash)).build() : highlights.getMenuTarget();
	}

	/** The objects and NPCs found now, with the ones a left click uses filled. */
	private SceneHighlights searchScene(SubStep current, MenuTarget target, boolean bank, int tick)
	{
		List<TileObject> objects = bank ? sceneObjects.banksNear(current.getWaypoint(), BANK_TILES)
			: offeringOption(sceneObjects.find(target.getObjectIds()), current.getOption());
		List<NPC> npcs = bank ? sceneObjects.bankersNear(current.getWaypoint(), BANK_TILES) : npcsFor(target);
		if (!bank)
		{
			colourCrewMenus(target, npcs);
		}
		String npcOption = bank || target.getPath().isEmpty() ? null : target.getPath().get(0);
		return SceneHighlights.builder().foundTick(tick)
			.emptySearches(scene.getFoundTick() == ActWatcher.NEVER ? 0 : scene.getEmptySearches() + 1)
			.objects(objects).npcs(npcs).fillObjects(bank ? List.of() : stepHighlights.fillObjects(objects, highlights))
			.npcOption(npcOption).fillNpcs(stepHighlights.fillNpcs(npcs, npcOption)).build();
	}

	private List<TileObject> offeringOption(List<TileObject> objects, String option)
	{
		return option == null ? objects
			: objects.stream().filter(object -> !leftClicks.isShownWithout(object, option)).collect(Collectors.toUnmodifiableList());
	}

	/**
	 * An act's NPC given by name (Fycie) is outlined only where core can't mark it. Shortest Path names one NPC per ride
	 * (a charter's Trader Crewmember), but a port's crew have several ids: when the
	 * named one isn't there, an NPC of the same name near the boarding tile with the ride's option is it, else any NPC
	 * there with that option (Port Sarim's boat to Port Piscarilius is Cabin Boy Herbert's, not Veos'). Its menu is
	 * coloured too.
	 */
	private List<NPC> npcsFor(MenuTarget target)
	{
		if (target.getNpcIds().isEmpty() && !target.getNpcNames().isEmpty())
		{
			return clueLink.coreMarksNpcs() ? List.of() : sceneObjects.npcsNamed(target.getNpcNames());
		}
		List<NPC> named = sceneObjects.npcs(target.getNpcIds());
		WorldPoint boarding = isApproach() ? getNext().getWaypoint() : getCurrent().getWaypoint();
		if (!named.isEmpty() || target.getNpcIds().isEmpty() || target.getPath().isEmpty() || boarding == null)
		{
			return named;
		}
		String name = client.getNpcDefinition(target.getNpcIds().iterator().next()).getName();
		List<NPC> offering = sceneObjects.npcsNear(boarding, RIDE_NPC_TILES, target.getPath().get(0));
		List<NPC> sameName = offering.stream().filter(npc -> npc.getName() != null && npc.getName().equalsIgnoreCase(name))
			.collect(Collectors.toUnmodifiableList());
		return sameName.isEmpty() ? offering : sameName;
	}

	/**
	 * A ride's crew found by name rather than id stand in for its named NPC in its menu too: their ids join the target's.
	 * A target that names its NPC only by name (Fycie) keeps its own option target instead.
	 */
	private void colourCrewMenus(MenuTarget target, List<NPC> npcs)
	{
		MenuTarget menuTarget = highlights.getMenuTarget();
		Set<Integer> ids = new HashSet<>(target.getNpcIds());
		npcs.forEach(npc -> ids.add(npc.getId()));
		if (!target.getNpcIds().isEmpty() && ids.size() > target.getNpcIds().size() && menuTarget.getNpcIds().equals(target.getNpcIds()))
		{
			highlights = highlights.toBuilder().menuTarget(menuTarget.toBuilder().npcIds(Set.copyOf(ids)).build()).build();
		}
	}

	private static boolean isInsideWayIn(Step step, WorldPoint at)
	{
		StepIndex.WayIn way = step == null ? null : step.getData().getEntry();
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
		rest.addAll(subSteps.acts(step));
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
		return replaceCurrent(current.toBuilder().text(KEEP_GOING + current.getWayIn().getText()).waypoint(toNearby ? to : null).build());
	}

	/** Part way in, the ground items to pick up first (the Mogre Camp's rocks): to the nearest, then on through the way. */
	private boolean pickUpOnTheWay(SubStep current, StepIndex.PickUp pickUp, WorldPoint tile)
	{
		int counted = pickUp.getVarbit() < 0 ? 0 : client.getVarbitValue(pickUp.getVarbit());
		int carried = Math.min(pickUp.getCount(), Math.max(counted, carriedCount(pickUp.getId())));
		boolean done = carried >= pickUp.getCount();
		return replaceCurrent(current.toBuilder()
			.text(done ? KEEP_GOING + pickUp.getThen() : "Pick up " + pickUp.getCount() + " " + pickUp.getName() + " (" + carried + "/" + pickUp.getCount() + ")")
			.detail(null).waypoint(done ? current.getWayIn().getTo() : nearest(tile)).build());
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
		return replaceCurrent(current.toBuilder().waypoint(nearest(tile)).build());
	}

	/** The pick-up tile nearest the player; null with no tile or none to pick up. */
	private WorldPoint nearest(WorldPoint tile)
	{
		return tile == null ? null : pickUpTiles.stream().min(Comparator.comparingInt(at -> at.distanceTo2D(tile))).orElse(null);
	}

	private int carriedCount(int itemId)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		return snapshot == null ? 0 : snapshot.count(Container.INVENTORY, itemId);
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

	public List<WorldPoint> getPickUpTiles()
	{
		return pickUpTiles;
	}

	/** The charter list's key for the port is known only once it is open: the step's text names it from then on. */
	private boolean keyFromCharterList()
	{
		char key = menuLines.charterKey(highlights.getMenuTarget());
		SubStep current = getCurrent();
		if (key == 0 || current == null || current.getText().contains(MenuLines.keyText(key)))
		{
			return false;
		}
		return replaceCurrent(current.toBuilder().text(LegSteps.withKey(current.getText(), key)).build());
	}

	private boolean watchDestinationSet()
	{
		MenuTarget menuTarget = highlights.getMenuTarget();
		List<String> path = menuTarget.getPath();
		SubStep current = getCurrent();
		boolean watching = current != null && current.getRow() != null && path.size() == 2 && SET_DESTINATION.equals(path.get(0));
		return obelisk.onGameTick(watching, watching && menuLines.showing(menuTarget), watching ? current.getRow().getName() : null);
	}

	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING || event.getGameState() == GameState.HOPPING)
		{
			scene = SceneHighlights.NONE;
		}
		else if (event.getGameState() == GameState.LOGGED_IN)
		{
			scene = scene.toBuilder().foundTick(ActWatcher.NEVER).build();
			refreshObjects();
		}
	}

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (isTravelStep() && mayStartTravel(event))
		{
			travelClickTick = client.getTickCount();
		}
		SubStep current = getCurrent();
		if (current == null || current.getKind() != SubStep.Kind.TELEPORT || current.getRow() == null)
		{
			return;
		}
		MenuTarget menuTarget = highlights.getMenuTarget();
		boolean onItem = menuTarget.getItemIds().contains(event.getItemId())
			|| menuTarget.getItemNames().contains(MenuLabels.normalise(event.getMenuTarget()));
		boolean onObject = menuTarget.getObjectIds().contains(event.getId());
		if (onItem || onObject)
		{
			teleportRefusal.clicked(client.getTickCount(), player.getPosition().getTile());
		}
	}

	/** MESBOX lines during a book change are the refusal backstop. */
	public void onChatMessage(ChatMessage message)
	{
		SubStep current = getCurrent();
		boolean gameLine = message.getType() == ChatMessageType.GAMEMESSAGE || message.getType() == ChatMessageType.MESBOX;
		if (gameLine && current != null && current.getKind() == SubStep.Kind.TELEPORT && current.getRow() != null)
		{
			teleportRefusal.onGameLine(current.getRow().getName(), client.getTickCount());
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
		changeRefusal.suspect(current.getChange().getName(), current.getBook(), client.getTickCount());
	}

	private void checkTeleportRefusal()
	{
		Player local = client.getLocalPlayer();
		SubStep current = getCurrent();
		String currentRow = current == null || current.getRow() == null ? null : current.getRow().getName();
		boolean standingStill = local != null && local.getAnimation() == NO_ANIMATION;
		String refused = teleportRefusal.refused(client.getTickCount(), currentRow, player.getPosition().getTile(), standingStill);
		if (refused != null)
		{
			log.debug("teleport refused: {}", refused);
			routes.onRowRefused(refused);
		}
	}

	private void checkRefusal()
	{
		String refused = changeRefusal.refused(client.getTickCount(), client.getVarbitValue(VarbitID.SPELLBOOK));
		if (refused != null)
		{
			routes.onRowRefused(refused);
		}
	}

	private void follow(Route committed)
	{
		Step step = routes.travelStep();
		WorldPoint tile = player.getPosition().getTile();
		noteDeposit(routeStep, step);
		if (routeStep == null || step == null || !routeStep.getKey().equals(step.getKey()))
		{
			stepSince = client.getTickCount();
		}
		route = committed;
		routeStep = step;
		boolean atStep = step != null && !itemsFirst(committed, step) && (stillAtAct(step, tile) || arrived(step));
		List<SubStep> base = step == null || committed == null && !atStep ? List.of() : atStep ? actsHere(step, tile)
			: subSteps.of(committed, step);
		steps = withoutRingHopsDone(deposit.before(base, tile));
		index = 0;
		jumpedThisSubStep = false;
		teleportAnimatedTick = ActWatcher.NEVER;
		travelClickTick = ActWatcher.NEVER;
		travelStarted = false;
		ringVanished = false;
		advance(tile);
		refreshGear();
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
		Fight next = view == null ? null : new Fight(view, again);
		boolean changed = !Objects.equals(next, fight);
		fight = next;
		return changed;
	}

	private void noteDeposit(Step ended, Step next)
	{
		boolean emote = ended != null && ClueTypes.EMOTE.equals(ended.getType()) && ended.getData().getStash() != null;
		boolean takenFromStash = actWatcher.holdsStashItems(stepSince);
		if (!emote || !takenFromStash || next != null && ended.getKey().equals(next.getKey()))
		{
			return;
		}
		StashMemory.State stash = stepNeeds.of(ended).getStash();
		List<String> back = itemsToPutBack(ended);
		if ((stash == StashMemory.State.FILLED || stash == StashMemory.State.EMPTY) && !back.isEmpty())
		{
			int stashId = ended.getRequirements() == null ? StepRequirements.NO_STASH : ended.getRequirements().getStashObjectId();
			deposit.start(ended.getData().getStash().getTile(), stashId, back, client.getTickCount());
		}
	}

	/** The ended emote's items still on the player, by the names of the ones carried; none once all are put back. */
	private List<String> itemsToPutBack(Step ended)
	{
		if (ended.getRequirements() == null)
		{
			return List.of();
		}
		Set<Integer> carried = player.carriedIds();
		List<String> back = new ArrayList<>();
		for (ItemRequirement need : ended.getRequirements().getItems())
		{
			carried.stream().filter(need::fulfilledBy).findFirst().ifPresent(id -> back.add(client.getItemDefinition(id).getName()));
		}
		return List.copyOf(back);
	}

	/** The box's left click is the place last taken from it, until a hover shows otherwise. */
	private void noteBoxUsed(SubStep done)
	{
		MenuTarget target = highlights.getMenuTarget();
		if (done.getKind() == SubStep.Kind.TELEPORT && done.getRow() != null && target.getLine() == MenuTarget.Line.BOX)
		{
			leftClicks.learnBoxUsed(target.getObjectIds(), done.getRow().destination());
		}
	}

	private void noteRingHop(SubStep done)
	{
		if (done.getKind() != SubStep.Kind.FAIRY_RING)
		{
			return;
		}
		String landingName = done.getRow().getName();
		boolean lastCode = done.getGoal() != null;
		int before = ringChain != null && ringChain.getLandingName().equals(landingName) ? ringChain.getHopsDone() : 0;
		ringChain = lastCode ? null : new RingChain(landingName, before + 1);
	}

	/** The chain's codes already dialled, left out of a plan made part way through it. */
	private List<SubStep> withoutRingHopsDone(List<SubStep> built)
	{
		int first = ringChain == null ? -1 : indexOfRingHop(built, 0);
		if (first < 0)
		{
			ringChain = null;
			return built;
		}
		List<SubStep> out = new ArrayList<>(built);
		for (int skipped = 0; skipped < ringChain.getHopsDone() && indexOfRingHop(out, first) == first; skipped++)
		{
			out.remove(first);
		}
		return List.copyOf(out);
	}

	/** The first of the chain's earlier codes at or after {@code from}, or -1. */
	private int indexOfRingHop(List<SubStep> in, int from)
	{
		for (int i = from; i < in.size(); i++)
		{
			SubStep step = in.get(i);
			if (step.getKind() == SubStep.Kind.FAIRY_RING && step.getGoal() == null
				&& step.getRow().getName().equals(ringChain.getLandingName()))
			{
				return i;
			}
		}
		return -1;
	}

	private boolean advance(WorldPoint tile)
	{
		boolean moved = false;
		while (index < steps.size() && isComplete(steps.get(index), tile))
		{
			noteRingHop(steps.get(index));
			noteBoxUsed(steps.get(index));
			index++;
			jumpedThisSubStep = false;
			teleportAnimatedTick = ActWatcher.NEVER;
		travelClickTick = ActWatcher.NEVER;
		travelStarted = false;
		ringVanished = false;
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
	 * step of several places or a probe trip never arrives this way: their NPCs and tiles belong to one stage. Nor does
	 * the NPC that only drops a key: the step is done at the object the key opens.
	 */
	/**
	 * Standing at the step isn't doing it while its item still has to come from the bank (Charlie's iron dagger), or is
	 * nowhere at all: the route's bank stop, or the box's list of what is missing, comes first.
	 */
	private boolean itemsFirst(Route committed, Step step)
	{
		if (committed != null)
		{
			return committed.isBanked();
		}
		Plan plan = routes.getPlan();
		return step.getKey().equals(plan.getStepKey()) && plan.getBest() == null && !plan.getRequirementNotes().isEmpty();
	}

	private boolean arrived(Step step)
	{
		PlanStart start = routes.getStart();
		WalkCosts walk = start == null ? null : start.getWalk();
		boolean parts = step.getStages().stream().allMatch(stage -> stage.getKind() == Stage.Kind.PART);
		if (walk == null || step.getWaypointAct() != null || !parts || ClueTypes.HOT_COLD.equals(step.getType()))
		{
			return false;
		}
		for (WorldPoint other : step.getOtherTiles())
		{
			if (walk.costNear(other) <= ARRIVED_WALK_TILES)
			{
				return true;
			}
		}
		List<String> npcs = step.getNpcsAtStep();
		if (npcs.isEmpty() || client.getTopLevelWorldView() == null)
		{
			return false;
		}
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			WorldPoint at = npc.getWorldLocation();
			if (npc.getName() != null && npcs.contains(npc.getName()) && walk.costNear(at) <= ARRIVED_WALK_TILES)
			{
				return true;
			}
		}
		return false;
	}

	private List<SubStep> actsHere(Step step, WorldPoint tile)
	{
		SubStep stageAct = actHere(step, tile);
		return stageAct.getStage() != null ? SubSteps.stageActs(stageAct.getStage()) : subSteps.acts(step);
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
		return nearest == null || nearest.getKind() == Stage.Kind.FINAL ? Acts.act(step) : SubSteps.stageAct(nearest);
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
		stepSince = ActWatcher.NEVER;
		actReachedStepKey = null;
		steps = List.of();
		index = 0;
		jumpedThisSubStep = false;
		teleportAnimatedTick = ActWatcher.NEVER;
		travelClickTick = ActWatcher.NEVER;
		travelStarted = false;
		ringVanished = false;
		lastTile = null;
		waiting = null;
		fight = null;
		boxView = null;
		pickUpTiles = List.of();
		deposit.clear();
		changeRefusal.clear();
		teleportRefusal.clear();
		obelisk.clear();
		highlights = Highlights.NONE;
		scene = SceneHighlights.NONE;
	}

	/** A teleport or book change is under way, until the player lands: its item, spell and tab are already used. */
	public boolean isTravelling()
	{
		boolean landing = !jumpedThisSubStep && teleportAnimatedTick != ActWatcher.NEVER
			&& client.getTickCount() - teleportAnimatedTick <= LANDING_TICKS;
		return isTravelAnimating() || landing;
	}

	private boolean isTravelAnimating()
	{
		return travelStarted && isAnimating();
	}

	/**
	 * A walk, an attack or a side panel tab never teleports: the hit or block animation that follows one while fighting
	 * isn't travel, and taking it for travel hides the step's highlights.
	 */
	private static boolean mayStartTravel(MenuOptionClicked event)
	{
		boolean tabStone = event.getMenuAction() == MenuAction.CC_OP
			&& TOPLEVELS.contains(WidgetUtil.componentToInterface(event.getParam1()));
		return event.getMenuAction() != MenuAction.WALK && !tabStone
			&& !ATTACK.equalsIgnoreCase(Text.removeTags(event.getMenuOption()));
	}

	private int travelStartTicks()
	{
		SubStep current = getCurrent();
		boolean minigame = current != null && current.getRow() != null && current.getRow().getCategory() == Category.MINIGAME;
		return minigame ? MINIGAME_TRAVEL_START_TICKS : TRAVEL_START_TICKS;
	}

	private boolean isTravelStep()
	{
		SubStep current = getCurrent();
		return current != null && (current.isTravel() || current.getChange() != null);
	}

	private boolean isAnimating()
	{
		Player local = client.getLocalPlayer();
		return local != null && local.getAnimation() != NO_ANIMATION;
	}

	/** A walk to NPCs ends once one of them is rendered close by (an elf in Prifddinas), not at the planned tile. */
	private boolean stageNpcNearby(SubStep step, WorldPoint tile)
	{
		return !step.getNpcIds().isEmpty() && tile != null && scene.getNpcs().stream()
			.anyMatch(npc -> npc.getWorldLocation().getPlane() == tile.getPlane()
				&& npc.getWorldLocation().distanceTo2D(tile) <= SubSteps.AREA_ARRIVE_TILES);
	}

	/**
	 * A walk to objects each player sees differently (Dorgesh-Kaan's broken lamps) ends once one offering the act is in the
	 * scene: the planned tile is any of them, and the act's line leads to the nearest.
	 */
	private boolean perPlayerObjectInSight(SubStep step)
	{
		return step.getOption() != null && !step.getObjectIds().isEmpty() && scene.getObjects().stream().anyMatch(leftClicks::changesForm);
	}

	private boolean isComplete(SubStep step, WorldPoint tile)
	{
		switch (step.getKind())
		{
			case WALK:
			case WALK_TO_CLUE:
				return step.getDone() == SubStep.Done.STASH_DEPOSITED && deposit.getAt() == null || PositionRules.near(tile, step.getGoal(), step.getRadius())
					|| step.getSpans().stream().anyMatch(place -> place.spans(tile)) || stageNpcNearby(step, tile)
					|| perPlayerObjectInSight(step);
			case WILDERNESS_EXIT:
				return tile != null && tile.getY() <= step.getGoal().getY();
			case FAIRY_RING:
				// A chained code's earlier rings land where no row says, or nowhere (DJQ): any landing, or the player
				// back after vanishing, ends them.
				return step.getGoal() == null ? jumpedThisSubStep || ringVanished && !isVanishing() : hasLanded(step, tile);
			case TELEPORT:
			case EXIT:
				return hasLanded(step, tile);
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
				throw new IllegalArgumentException("unhandled sub-step kind " + step.getKind());
		}
	}

	private boolean isRingStep()
	{
		SubStep current = getCurrent();
		return current != null && current.getKind() == SubStep.Kind.FAIRY_RING;
	}

	private boolean isVanishing()
	{
		Player local = client.getLocalPlayer();
		return local != null && local.getAnimation() == AnimationID.HUMAN_FAIRY_VANISH;
	}

	/** Any floor: Shortest Path puts some landings on the wrong one (the diary cape's Kaleb, Slepe). */
	private boolean hasLanded(SubStep step, WorldPoint tile)
	{
		return jumpedThisSubStep && tile != null && tile.distanceTo2D(step.getGoal()) <= step.getRadius();
	}

	private boolean isActDone(SubStep act)
	{
		switch (act.getDone())
		{
			case EQUIPPED:
			case WAY_IN_WORN:
			case GEAR_WORN:
			case SLOTS_EMPTY:
				return gearActs.isDone(act, routeStep);
			case STASH_DEPOSITED:
				return deposit.getAt() == null || actWatcher.isDone(act.getDone(), stepSince);
			case STASH_WITHDRAWN:
				return actWatcher.isDone(act.getDone(), stepSince) || !subSteps.takeFromStash(routeStep);
			case ITEM_CARRIED:
				return player.carriesItemNamed(act.getCarriedWhenDone());
			case ITEM_GONE:
				return !player.holdsOrWearsItemNamed(act.getActItemNamed());
			case ITEMS_GONE:
				return act.getActItems().stream().noneMatch(player.carriedIds()::contains);
			case ITEM_HELD:
				return act.getHeldWhenDone().stream().anyMatch(player.carriedIds()::contains);
			default:
				return actWatcher.isDone(act.getDone(), stepSince);
		}
	}

	/** The scene is searched again for the new sub-step's objects and NPCs. */
	private void updateHighlights()
	{
		scene = SceneHighlights.NONE;
		highlights = stepHighlights.of(getCurrent(), routeStep);
	}

	/** Walking to the STASH unit the next act searches: it is outlined from the walk on, like a bank. */
	private int stashAhead()
	{
		SubStep current = getCurrent();
		SubStep next = getNext();
		return current != null && current.getKind() == SubStep.Kind.WALK && next != null ? stepHighlights.stashOf(next, routeStep) : StepRequirements.NO_STASH;
	}

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
		for (int id : player.inventoryIds())
		{
			String name = client.getItemDefinition(id).getName().toLowerCase(Locale.ROOT);
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

	/** A gear act listing what to put on or take off now. */
	private boolean refreshGear()
	{
		SubStep current = getCurrent();
		return current != null && replaceCurrent(gearActs.refresh(current, routeStep));
	}

	/** The current sub-step rewritten; the caller's tick updates the highlights once for every change. */
	private boolean replaceCurrent(SubStep listed)
	{
		if (listed.equals(getCurrent()))
		{
			return false;
		}
		List<SubStep> updated = new ArrayList<>(steps);
		updated.set(index, listed);
		steps = List.copyOf(updated);
		return true;
	}

	/** A task act made in steps says the step it is at under the act. */
	private boolean showMaking()
	{
		SubStep current = getCurrent();
		String detail = current != null && current.isTask() ? taskActs.detail(routeStep) : null;
		return detail != null && replaceCurrent(current.toBuilder().detail(detail).build());
	}

	private void notifyListeners()
	{
		listeners.forEach(Runnable::run);
	}

	@Value
	private static class Waiting
	{
		Route route;
		/** Where the player stood when it came: a route from farther away than a step is stale. */
		WorldPoint from;
	}

	/** A chained fairy ring part way through: the plan made where a code lands starts the chain over. */
	@Value
	private static class RingChain
	{
		String landingName;
		int hopsDone;
	}

	/** The fight shown while an enemy attacks, and the act to do again after it. */
	@Value
	private static class Fight
	{
		SubStep view;
		SubStep again;
	}
}
