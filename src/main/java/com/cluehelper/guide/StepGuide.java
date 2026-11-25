package com.cluehelper.guide;

import com.cluehelper.link.ClueLink;
import com.cluehelper.memory.*;
import com.cluehelper.player.*;
import com.cluehelper.route.*;
import com.cluehelper.step.*;
import com.cluehelper.transport.Category;
import com.cluehelper.transport.MenuLine;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.*;
import net.runelite.client.util.Text;
import com.cluehelper.guide.SubStep.Kind;

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
	/** An object's own location and the tile a click on it names can differ by its size. */
	private static final int TASK_OBJECT_TILES = 2;

	private static final String CAPE_LIMIT_TEXT = "five times per day";
	private static final int NO_ANIMATION = -1;
	/** A teleport's animation can end a tick or two before the player lands. */
	private static final int LANDING_TICKS = 3;
	/** A teleport animates on its click's tick or within two: any other animation (a block while being hit) isn't travel. */
	private static final int TRAVEL_START_TICKS = 2;
	/** The Grouping teleport starts its animation 3-6 ticks after the click. */
	private static final int MINIGAME_TRAVEL_START_TICKS = 7;
	private static final int OBJECT_REFRESH_TICKS = 5;
	/** A bank node sits on the walkable tile in front of its booth, chest or banker. */
	private static final int BANK_TILES = 3;
	/** An exit's object stands beside the tile it is taken from; a staircase spans a few tiles. */
	private static final int EXIT_OBJECT_TILES = 3;
	/** How far from its boarding tile a ride's NPC may stand. */
	private static final int RIDE_NPC_TILES = 8;
	/**
	 * Core's tile can be on another floor than its NPC (Donovan stands upstairs, his tile is on the ground floor), so a
	 * reached act stays while the player is this close to a target on any plane.
	 */
	private static final int STAY_AT_ACT_TILES = 20;
	/** How far a floor up or down weighs against tiles on the same floor when picking the nearest place. */
	private static final int OTHER_FLOOR_TILES = 50;
	/** In scene-walk tiles: 20 ticks of running. */
	private static final int ARRIVED_WALK_TILES = 40;
	/** Part way in, the line leads on to where the way ends when that is this close on this floor (the cavern, not the pyramid's top). */
	private static final int PART_WAY_LINE_TILES = 60;
	private static final String ATTACK = "Attack";
	/** The fixed, resizable classic and resizable modern layouts, whose stones open the side panel tabs. */
	private static final Set<Integer> TOPLEVELS = Set.of(InterfaceID.TOPLEVEL, InterfaceID.TOPLEVEL_OSRS_STRETCH,
		InterfaceID.TOPLEVEL_PRE_EOC);

	@Inject
	Client client;
	@Inject
	RoutePlanner routes;
	@Inject
	PlayerModel player;
	@Inject
	BankMemory bankMemory;
	@Inject
	WayInMemory wayInMemory;
	@Inject
	UnlockMemory unlocks;
	@Inject
	MenuLines menuLines;
	@Inject
	RowTargets rowTargets;
	@Inject
	GearActs gearActs;
	@Inject
	TaskActs taskActs;
	@Inject
	StepHighlights stepHighlights;
	@Inject
	ActWatcher actWatcher;
	@Inject
	StepNeeds stepNeeds;
	@Inject
	SubSteps subSteps;
	@Inject
	SceneObjects sceneObjects;
	@Inject
	AbyssObstacles abyssObstacles;
	@Inject
	ClueLink clueLink;
	final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private static final String TALK_TO = "Talk to ";
	@Inject
	LeftClicks leftClicks;
	@Inject
	DepositReminder deposit;
	final ChangeRefusal changeRefusal = new ChangeRefusal();
	final TeleportRefusal teleportRefusal = new TeleportRefusal();
	@Inject
	ObeliskDestination obelisk;

	Runnable unregister;
	@Getter
	Route route;
	Step routeStep;
	/** When the guided step began: an act's event counts from then. */
	int stepSince = ActWatcher.NEVER;
	String actReachedStepKey;
	@Getter
	List<SubStep> steps = List.of();
	int index;
	boolean jumpedThisSubStep;
	int teleportAnimatedTick = ActWatcher.NEVER;
	int travelClickTick = ActWatcher.NEVER;
	/** The travel sub-step's own animation is playing: one that started right after a click during it. */
	boolean travelStarted;
	/**
	 * The fairy ring sub-step's code took the player away: the leaving animation played during it. The last ring's
	 * arrival animation is still playing when the next code is dialled, so any animation won't do.
	 */
	boolean ringVanished;
	WorldPoint lastTile;
	/** Where the player stood before the last teleport. */
	WorldPoint jumpedFrom;
	/** The step whose task the player has started on its object (a pyre built, a net trap set), or null. */
	String taskStartedKey;
	/** The step whose items went into an object (the mangled bones, into the pyre ship): it finishes there. */
	String spentStepKey;
	/** The clicked object's tile, for {@link #taskStartedKey}. */
	WorldPoint taskStartedAt;
	/** A route that came while a teleport was under way, followed once it lands. */
	Waiting waiting;
	/** While an enemy attacks during a dig or an emote. */
	Fight fight;
	boolean onActTile;
	RingChain ringChain;
	/** After a talk that hands over a puzzle or light box: open it, solve it, then talk again. */
	SubStep boxView;
	/** Ground items to pick up now, by tile. */
	@Getter
	List<WorldPoint> pickUpTiles = List.of();
	@Getter
	Highlights highlights = Highlights.NONE;
	SceneHighlights scene = SceneHighlights.NONE;

	public void start()
	{
		unregister = routes.addListener(this::onPlan);
		routes.holdWhile(() -> isTravelling() || isTravelJustClicked());
		obelisk.start();
	}

	public void stop()
	{
		if (unregister != null)
		{
			unregister.run();
			unregister = null;
		}
		obelisk.stop();
		clear();
	}

	/** Listeners run on the client thread. */
	public Runnable addListener(Runnable listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** While an enemy attacks during a dig or an emote, the fight; after it, the act again. */
	public SubStep getCurrent()
	{
		if (fight != null)
		{
			return fight.getView();
		}
		return boxView != null ? boxView : listedAt(index);
	}

	/** During a fight, the act to do again. Past a bank stop, what follows its task, which the stop shows already. */
	public SubStep getNext()
	{
		if (fight != null && fight.getAgain() != null)
		{
			return fight.getAgain();
		}
		SubStep listed = listedAt(index);
		int at = index + 1;
		while (listed != null && listedAt(at) != null && (listedAt(at).getText().equals(listed.getText()) || isDonePreparation(listedAt(at))))
		{
			at++;
		}
		SubStep next = listedAt(at);
		return next == null ? null : taskActs.phased(next, routeStep);
	}

	/**
	 * Items built into an object by one of the step's own acts (the bones into the pyre ship) are not fetched again: the
	 * step finishes where they went, whatever a new plan says.
	 */
	private void noteItemsSpent(SubStep done)
	{
		boolean spent = done.getKind() == Kind.ACT && done.getDone() == SubStep.Done.ITEMS_GONE && routeStep != null
			&& routeStep.getData().getBefore().stream().anyMatch(before -> !before.getGone().isEmpty() && before.getText().equals(done.getText()));
		if (spent)
		{
			spentStepKey = routeStep.getKey();
		}
	}

	/** Gear already on, or an item already held: passed over at once when reached, so not shown next. */
	private boolean isDonePreparation(SubStep sub)
	{
		return sub.getKind() == Kind.ACT && sub.isPreparation() && isActDone(sub);
	}

	/** The listed sub-step at {@code at}, or null past the end. */
	private SubStep listedAt(int at)
	{
		return at < steps.size() ? steps.get(at) : null;
	}

	/**
	 * The carried item used on the outlined objects: a way in's (a rope on the crevice), or what a planting task's patch
	 * takes next (rake, spade or seed); -1 for none.
	 */
	public int getItemUsedOnObjects()
	{
		if (highlights.getUsedItem() >= 0)
		{
			return highlights.getUsedItem();
		}
		List<TileObject> patch = scene.getObjects();
		return isAtOrToTask(getCurrent()) && !patch.isEmpty() ? taskActs.itemForPatch(routeStep, patch.get(0)) : -1;
	}

	/** A planting task's patches, or the walk to them: outlined as ground, as the objects an item is used on are. */
	public boolean isPlantingPatch()
	{
		return isAtOrToTask(getCurrent()) && routeStep != null && routeStep.getData().isPlants();
	}

	/** How many of the item one use takes: a patch is planted with three seeds. */
	public int getItemUsedOnObjectsQuantity(int itemId)
	{
		return routeStep == null ? 1 : routeStep.getData().getAmounts().getOrDefault(itemId, 1);
	}

	/** A task's act, or the walk to it: the patch is outlined from the walk on, as at the task. */
	private boolean isAtOrToTask(SubStep current)
	{
		SubStep next = listedAt(index + 1);
		return current != null && (current.isTask() || current.getKind() == Kind.WALK && next != null && next.isTask());
	}

	/**
	 * Walking to a located teleport's object or NPC (Renu, a spirit tree): like a bank, it is outlined from the walk on
	 * and the line leads to it.
	 */
	public boolean isApproach()
	{
		SubStep current = getCurrent();
		SubStep next = getNext();
		boolean toTeleport = next != null && next.getKind() == Kind.TELEPORT && next.getRow() != null && next.getRow().isLocated();
		return current != null && current.getKind() == Kind.WALK && (!current.getNpcIds().isEmpty() || toTeleport);
	}

	/** A staircase, ladder, door or NPC that is the way out or in: outlined and walked to like a bank. */
	public boolean isExit()
	{
		SubStep current = getCurrent();
		return current != null && (current.getKind() == Kind.EXIT || current.getKind() == Kind.ENTER);
	}

	public List<TileObject> getHighlightObjects()
	{
		return scene.getObjects();
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
				steps = withRememberedWayIn(inWayIn(step, goIn), step);
				index = 0;
				updateHighlights();
				refreshObjects();
				notifyListeners();
			}
			return;
		}
		if (isPlannedBeforeJump(start) || isPlannedBeforeGoingIn(step, routes.getCommitted()))
		{
			return;
		}
		Route committed = routes.getCommitted();
		boolean sameStep = step != null && routeStep != null && step.getKey().equals(routeStep.getKey());
		if (committed != null && committed.isBanked() && sameStep && isTaskUnderway() || sameStep && step.getKey().equals(spentStepKey))
		{
			return;
		}
		if (isTravelling() || isTravelJustClicked())
		{
			waiting = new Waiting(committed, player.getPosition().getTile());
			return;
		}
		follow(committed);
	}

	/**
	 * A task begun on its object goes on there, not back to a bank: its items are in the object now (the pyre logs, built
	 * into the pyre), so the step's needs no longer read as carried.
	 */
	/** The task's spell cast on one of its items (the ensouled head): the head is spent, the task goes on where it was cast. */
	private boolean isTaskCastItem(int itemId)
	{
		StepIndex.Cast cast = routeStep == null ? null : routeStep.getData().getCast();
		return cast != null && itemId > 0 && cast.getOn().contains(itemId) && client.isWidgetSelected();
	}

	/** A task done with its own items (logs lit), begun on one of them: they are gone before core marks the task done. */
	private boolean usesTaskItem(SubStep current, int itemId)
	{
		return current != null && current.isTask() && routeStep != null && itemId > 0
			&& routeStep.getRequirements().getItems().stream().anyMatch(item -> item.fulfilledBy(itemId));
	}

	private boolean isTaskObject(int objectId)
	{
		return routeStep != null && routeStep.getStages().stream()
			.anyMatch(stage -> stage.getKind() == Stage.Kind.TASK && stage.getObjectIds().contains(objectId));
	}

	/**
	 * Once the task is under way, only the object it was begun on: a net trap's tree is no young tree any more, and the
	 * next young tree is not where the catch comes. A patch to plant in is already the one patch, and nearby keeps only
	 * part of an L-shaped one.
	 */
	private List<TileObject> whereTaskBegan(List<TileObject> objects)
	{
		if (!isTaskUnderway() || taskStartedAt == null || routeStep.getData().isPlants())
		{
			return objects;
		}
		return objects.stream().filter(object -> object.getWorldLocation().distanceTo(taskStartedAt) <= TASK_OBJECT_TILES)
			.collect(Collectors.toUnmodifiableList());
	}

	private boolean isTaskUnderway()
	{
		SubStep current = getCurrent();
		return current != null && current.isTask() && routeStep != null && routeStep.getKey().equals(taskStartedKey);
	}

	/**
	 * A plan searched while a teleport was cast, from where it started, lands after it: its start's items are spent
	 * (the spell's runes) and its first leg is behind the player. The plan from the landing follows.
	 */
	private boolean isPlannedBeforeJump(PlanStart start)
	{
		WorldPoint here = player.getPosition().getTile();
		return start != null && jumpedFrom != null && !PositionRules.teleported(start.getTile(), jumpedFrom)
			&& PositionRules.teleported(start.getTile(), here);
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
			scene = withFills(scene);
		}
	}

	/** Call from a GameTick handler that runs after the planner's. */
	public void onGameTick()
	{
		WorldPoint tile = player.getPosition().getTile();
		wayInMemory.onTick(wayInOf(routeStep), tile);
		boolean jumped = PositionRules.teleported(lastTile, tile);
		WorldPoint from = lastTile;
		lastTile = tile;
		jumpedThisSubStep |= jumped;
		if (jumped)
		{
			jumpedFrom = from;
			actReachedStepKey = null;
			// A new scene (out of a cave) is searched at once, not on the slow refresh
			scene = SceneHighlights.NONE;
		}
		travelStarted = isTravelStep() && isAnimating() && (travelStarted || isTravelJustClicked());
		if (travelStarted && getCurrent().isTravel())
		{
			teleportAnimatedTick = client.getTickCount();
		}
		ringVanished |= isRingStep() && isVanishing();
		obelisk.onGameTick(getCurrent());
		// Before the early returns below: a follow on every tick near the step would starve them (a deposit never seen)
		actWatcher.onGameTick(routeStep);
		deposit.onGameTick(tile);
		if (waiting != null && !isTravelling() && !isTravelJustClicked())
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
		boolean changed = advance(tile);
		changed |= refreshFight();
		changed |= refreshOnActTile(tile);
		changed |= refreshBox();
		changed |= obelisk.takeChanged();
		changed |= keyFromOpenList();
		changed |= partWayIn(tile);
		changed |= dropToPickUp();
		changed |= pickUpForAct(tile);
		changed |= refreshCurrent();
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
		SubStep current = withClueTileObject(getCurrent());
		boolean bank = current != null && current.getKind() == Kind.BANK;
		SubStep ahead = objectActAhead(current);
		SubStep coresAhead = ahead == null ? coresObjectActAhead(current) : null;
		SubStep optionOf = ahead != null ? ahead : coresAhead != null ? coresAhead : current;
		List<String> option = optionPath(optionOf);
		if (ahead != null)
		{
			highlights = highlights.toBuilder().objectOption(ahead.getOption()).build();
		}
		MenuTarget target = outlineTarget(current, option);
		MenuTarget optionTarget = coresAhead == null ? target
			: MenuTarget.builder().path(option).objectIds(coresAhead.getObjectIds()).build();
		boolean ownOption = !option.isEmpty() && highlights.getMenuTarget() == MenuTarget.NONE;
		highlights = highlights.toBuilder().optionTarget(ownOption ? optionTarget : MenuTarget.NONE).build();
		boolean nothingToOutline = target.getObjectIds().isEmpty() && target.getNpcIds().isEmpty() && target.getNpcNames().isEmpty();
		if (nothingToOutline && !bank || isCoresOwnObject(current))
		{
			scene = scene.toBuilder().objects(List.of()).npcs(List.of()).fillObjects(List.of()).fillNpcs(List.of()).build();
			return;
		}
		int tick = client.getTickCount();
		boolean nothingYet = scene.isEmpty() && scene.getEmptySearches() < OBJECT_REFRESH_TICKS;
		boolean npcGone = scene.getNpcs().stream().anyMatch(npc -> !sceneObjects.isLoaded(npc));
		if (scene.getFoundTick() == ActWatcher.NEVER || nothingYet || npcGone || tick - scene.getFoundTick() >= OBJECT_REFRESH_TICKS)
		{
			scene = searchScene(current, target, bank, tick);
		}
		if (bank)
		{
			highlights = highlights.toBuilder().optionTarget(bankOptions()).build();
		}
	}

	/** The found booths' and bankers' "Bank", and "Use" on a bank chest that has no "Bank". */
	private MenuTarget bankOptions()
	{
		Set<Integer> chests = scene.getObjects().stream().filter(object -> !leftClicks.offers(object, StepHighlights.BANK_OPTION))
			.map(TileObject::getId).collect(Collectors.toUnmodifiableSet());
		MenuTarget useOnChests = MenuTarget.builder().path(List.of(Acts.USE_OPTION)).objectIds(chests).build();
		return MenuTarget.builder().path(List.of(StepHighlights.BANK_OPTION))
			.objectIds(scene.getObjects().stream().map(TileObject::getId).collect(Collectors.toUnmodifiableSet()))
			.npcIds(scene.getNpcs().stream().map(NPC::getId).collect(Collectors.toUnmodifiableSet()))
			.alternatives(List.of(useOnChests)).build();
	}

	/**
	 * The act after the walk to the clue when it is on an object core doesn't mark (Juna, which core takes for an NPC):
	 * outlined, filled and its option coloured from afar, as the act would; else null.
	 */
	private SubStep objectActAhead(SubStep current)
	{
		SubStep next = objectActAfterWalk(current);
		return next != null && !isCoresOwnObject(next) ? next : null;
	}

	/** The act after the walk to the clue when it is on core's own object (a crate searched): its option is coloured from afar. */
	private SubStep coresObjectActAhead(SubStep current)
	{
		SubStep next = objectActAfterWalk(current);
		return next != null && next.getOption() != null && isCoresOwnObject(next) ? next : null;
	}

	/** Listed right after the walk, not {@link #getNext}: that skips an act reading as the walk does ("Search the crate"). */
	private SubStep objectActAfterWalk(SubStep current)
	{
		SubStep next = withClueTileObject(listedAt(index + 1));
		boolean act = current != null && current.getKind() == Kind.WALK_TO_CLUE && next != null && next.getKind() == Kind.ACT
			&& !next.getObjectIds().isEmpty();
		return act ? next : null;
	}

	/**
	 * A search act on what stands on the clue's tile now, when that is another form than core marks (a wardrobe opened)
	 * or core knows the place alone (Ardougne's drawers): outlined by us then. Core's own form keeps core's ids.
	 */
	private SubStep withClueTileObject(SubStep act)
	{
		if (!isSearchAct(act) || routeStep == null)
		{
			return act;
		}
		Set<Integer> onTile = clueTileObjects().stream().map(TileObject::getId).collect(Collectors.toUnmodifiableSet());
		boolean coresForm = onTile.stream().anyMatch(routeStep.getObjectIds()::contains);
		return onTile.isEmpty() || coresForm ? act : act.toBuilder().objectIds(onTile).build();
	}

	/** The objects on the clue's tiles that are searched, or opened to be searched. */
	private List<TileObject> clueTileObjects()
	{
		return routeStep.getTargets().stream().flatMap(tile -> sceneObjects.on(tile).stream())
			.filter(object -> leftClicks.offers(object, StepHighlights.SEARCH_OPTION) || leftClicks.offers(object, StepHighlights.OPEN_OPTION))
			.collect(Collectors.toUnmodifiableList());
	}

	private static boolean isSearchAct(SubStep act)
	{
		return act != null && act.getKind() == Kind.ACT && StepHighlights.SEARCH_OPTION.equals(act.getOption());
	}

	/** A closed wardrobe or chest is opened before it is searched: its Open is marked until it shows Search. */
	private static List<String> optionPath(SubStep act)
	{
		if (act == null || act.getOption() == null)
		{
			return List.of();
		}
		return isSearchAct(act) ? List.of(StepHighlights.OPEN_OPTION, act.getOption()) : List.of(act.getOption());
	}

	/** An act on the clue's own object (the spirit tree), which core marks already: only its option is coloured. */
	private boolean isCoresOwnObject(SubStep current)
	{
		return current != null && current.getKind() == Kind.ACT && !current.getObjectIds().isEmpty()
			&& routeStep != null && routeStep.getObjectIds().containsAll(current.getObjectIds());
	}

	/**
	 * What the scene is searched for: the sub-step's own NPCs or objects with its option, the furniture a walk leads to,
	 * the STASH unit the next act searches, else the menu target's objects.
	 */
	private MenuTarget outlineTarget(SubStep current, List<String> option)
	{
		MenuTarget.MenuTargetBuilder withOption = MenuTarget.builder().path(option);
		if (current != null && !current.getNpcIds().isEmpty())
		{
			return withOption.npcIds(current.getNpcIds()).build();
		}
		if (current != null && !current.getObjectIds().isEmpty())
		{
			return withOption.objectIds(current.getObjectIds()).build();
		}
		if (current != null && current.getWayIn() != null && !current.getWayIn().getUse().isEmpty())
		{
			return withOption.objectIds(Set.of(current.getWayIn().getObject())).build();
		}
		if (current != null && !current.getNpcNames().isEmpty())
		{
			return withOption.npcNames(current.getNpcNames()).build();
		}
		if (isApproach())
		{
			return rowTargets.located(getNext().getRow());
		}
		SubStep ahead = objectActAhead(current);
		if (ahead != null)
		{
			return withOption.objectIds(ahead.getObjectIds()).build();
		}
		int stash = stashAhead();
		return stash != StepRequirements.NO_STASH ? MenuTarget.builder().objectIds(Set.of(stash)).build() : highlights.getMenuTarget();
	}

	/** The objects and NPCs found now, with the ones a left click uses filled. */
	private SceneHighlights searchScene(SubStep current, MenuTarget target, boolean bank, int tick)
	{
		List<TileObject> objects = bank ? sceneObjects.banksNear(current.getWaypoint(), BANK_TILES)
			: target.getObjectIds().stream().anyMatch(AbyssObstacles::isObstacle) ? abyssObstacle()
			: whereTaskBegan(onlyPatchToUse(current, atExit(current, onClueTiles(target, offeringOption(sceneObjects.find(target.getObjectIds()), current.getOption())))));
		List<NPC> npcs = bank ? sceneObjects.bankersNear(current.getWaypoint(), BANK_TILES) : npcsFor(target);
		if (!bank)
		{
			colourCrewMenus(target, npcs);
		}
		String npcOption = bank || target.getPath().isEmpty() ? null : target.getPath().get(0);
		return withFills(SceneHighlights.builder().foundTick(tick)
			.emptySearches(scene.getFoundTick() == ActWatcher.NEVER ? 0 : scene.getEmptySearches() + 1)
			.objects(objects).npcs(npcs).bank(bank).npcOption(npcOption).build());
	}

	/** A search outlines the object on the clue's tile, not every drawers of its id in town. */
	private List<TileObject> onClueTiles(MenuTarget target, List<TileObject> objects)
	{
		if (routeStep == null || !target.getPath().contains(StepHighlights.SEARCH_OPTION))
		{
			return objects;
		}
		List<TileObject> there = objects.stream().filter(object -> routeStep.getTargets().contains(object.getWorldLocation()))
			.collect(Collectors.toUnmodifiableList());
		return there.isEmpty() ? objects : there;
	}

	/** The found objects and NPCs whose left click does what the step needs: at a bank stop, opens the bank. */
	private SceneHighlights withFills(SceneHighlights found)
	{
		if (found.isBank())
		{
			return found.toBuilder().fillObjects(stepHighlights.fillBanks(found.getObjects()))
				.fillNpcs(stepHighlights.fillBankers(found.getNpcs())).build();
		}
		return found.toBuilder().fillObjects(stepHighlights.fillObjects(found.getObjects(), highlights))
			.fillNpcs(stepHighlights.fillNpcs(found.getNpcs(), found.getNpcOption())).build();
	}

	/** The Abyss's outer ring is crossed by the obstacle the path line leads to, not the one spot the walk data names. */
	private List<TileObject> abyssObstacle()
	{
		TileObject obstacle = abyssObstacles.choose();
		return obstacle == null ? List.of() : List.of(obstacle);
	}

	/**
	 * An exit's own object, not another of the same id (the Mos Le'Harmless cave's two staircases): the one by the tile
	 * the exit is taken from, or all of them when none stands there.
	 */
	private static List<TileObject> atExit(SubStep current, List<TileObject> objects)
	{
		WorldPoint from = current != null && current.getKind() == Kind.EXIT ? current.getExit().getOrigin() : null;
		List<TileObject> near = from == null ? List.of() : objects.stream()
			.filter(object -> object.getWorldLocation().distanceTo(from) <= EXIT_OBJECT_TILES).collect(Collectors.toUnmodifiableList());
		return near.isEmpty() ? objects : near;
	}

	/** A planting task outlines the one patch to plant in, all its pieces (an L-shaped patch is two), not every patch. */
	private List<TileObject> onlyPatchToUse(SubStep current, List<TileObject> objects)
	{
		TileObject patch = isAtOrToTask(current) ? taskActs.patchToUse(routeStep, objects) : null;
		return patch == null ? objects
			: objects.stream().filter(piece -> piece.getId() == patch.getId()).collect(Collectors.toUnmodifiableList());
	}

	private List<TileObject> offeringOption(List<TileObject> objects, String option)
	{
		return option == null ? objects
			: objects.stream().filter(object -> !leftClicks.isShownWithout(object, option)).collect(Collectors.toUnmodifiableList());
	}

	/**
	 * An act's NPC given by name (Fycie) is found by name; the overlay leaves it to core while core marks it. Shortest
	 * Path names one NPC per ride (a charter's Trader Crewmember), but a port's crew have several ids: when the named one
	 * isn't there, an NPC of the same name near the boarding tile with the ride's option is it, else any NPC there with
	 * that option (a boat's ride may be another NPC's than the one the row names).
	 */
	private List<NPC> npcsFor(MenuTarget target)
	{
		if (target.getNpcIds().isEmpty() && !target.getNpcNames().isEmpty())
		{
			return sceneObjects.npcsNamed(target.getNpcNames());
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

	/**
	 * A plan searched from outside the way in, published once the player is through it: its route's first leg starts
	 * outside. The plan from inside follows.
	 */
	private boolean isPlannedBeforeGoingIn(Step step, Route committed)
	{
		WorldPoint at = player.getPosition() == null ? null : player.getPosition().getTile();
		WorldPoint plannedFrom = committed == null || committed.getLegs().isEmpty() ? null : committed.getLegs().get(0).getFrom();
		return plannedFrom != null && at != null && isInsideWayIn(step, at) && !isInsideWayIn(step, plannedFrom);
	}

	private static StepIndex.WayIn wayInOf(Step step)
	{
		return step == null ? null : step.getData().getEntry();
	}

	/** A way in entered at one of several places leads to the one the player used last (the crypt with the tunnel). */
	private List<SubStep> withRememberedWayIn(List<SubStep> listed, Step step)
	{
		StepIndex.WayIn way = wayInOf(step);
		WayInMemory.Entered entered = wayInMemory.enteredBy(way);
		if (entered == null)
		{
			return way == null || way.getTried().isEmpty() ? listed : withWayInTried(listed, way);
		}
		WorldPoint at = entered.getFrom();
		boolean clicked = entered.getObject() >= 0;
		Set<Integer> objects = clicked ? Set.of(entered.getObject()) : Set.copyOf(way.getTried());
		String option = clicked ? entered.getOption() : way.getOption();
		return listed.stream().map(sub -> sub.getWayIn() == way && (sub.getKind() == Kind.ENTER || sub.getKind() == Kind.WALK)
			? sub.toBuilder().text(way.getRememberedAct()).waypoint(at).goal(sub.getGoal() == null ? null : at)
				.objectIds(objects).option(option).build() : sub)
			.collect(Collectors.toUnmodifiableList());
	}

	/** Not known yet where the way leads in: its objects are each tried (a sarcophagus searched in each crypt). */
	private static List<SubStep> withWayInTried(List<SubStep> listed, StepIndex.WayIn way)
	{
		return listed.stream().map(sub -> sub.getWayIn() == way && sub.getKind() == Kind.ENTER
			? sub.toBuilder().objectIds(Set.copyOf(way.getTried())).option(way.getOption()).build() : sub)
			.collect(Collectors.toUnmodifiableList());
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
			rest.add(LegSteps.walkToClue(step.getTargets().get(0), SubSteps.ARRIVE_TILES, StepIndex.Entry.NONE));
		}
		rest.addAll(subSteps.acts(step));
		return SubSteps.withWayInEquipped(rest, step.getData(), player::finished, player.carriedIds(), player.carriedIds(),
			id -> client.getItemDefinition(id).getName());
	}

	/** Part way up a way in (the pyramid's lower levels), the step points on, not back to its ground-floor start. */
	private boolean partWayIn(WorldPoint tile)
	{
		SubStep current = getCurrent();
		StepIndex.WayIn way = current != null && current.getKind() == Kind.ENTER && current.getWayIn().isPartWay(tile)
			? current.getWayIn() : null;
		StepIndex.PickUp pickUp = way == null ? null : way.getPickUp();
		pickUpTiles = pickUp != null ? sceneObjects.groundItemTiles(pickUp.getId()) : List.of();
		if (pickUp != null)
		{
			return pickUpOnTheWay(current, pickUp, tile);
		}
		if (way == null)
		{
			return false;
		}
		WorldPoint to = way.getTo();
		boolean toNearby = !way.isNoLine() && to != null && tile != null && to.getPlane() == tile.getPlane()
			&& to.distanceTo2D(tile) <= PART_WAY_LINE_TILES;
		return replaceCurrent(current.toBuilder().text(way.getPartWayLine()).waypoint(toNearby ? to : null).build());
	}

	/**
	 * Part way in, the ground items to pick up first (the Mogre Camp's rocks): to the one that adds least to the way on,
	 * then on through the way.
	 */
	private boolean pickUpOnTheWay(SubStep current, StepIndex.PickUp pickUp, WorldPoint tile)
	{
		int counted = pickUp.getVarbit() < 0 ? 0 : client.getVarbitValue(pickUp.getVarbit());
		int carried = Math.min(pickUp.getCount(), Math.max(counted, carriedCount(pickUp.getId())));
		boolean done = carried >= pickUp.getCount();
		return replaceCurrent(current.toBuilder()
			.text(done ? KEEP_GOING + pickUp.getThen() : "Pick up " + pickUp.getCount() + " " + pickUp.getName() + " (" + carried + "/" + pickUp.getCount() + ")")
			.detail(null).waypoint(done ? current.getWayIn().getTo() : onTheWay(tile, current.getWayIn().getTo(), pickUp)).build());
	}

	/**
	 * The pick-up tile with the shortest walk from the player through it to {@code on}: of those in sight, else of where
	 * they lie, as the game shows them only nearby. Null with none known.
	 */
	private WorldPoint onTheWay(WorldPoint tile, WorldPoint on, StepIndex.PickUp pickUp)
	{
		List<WorldPoint> tiles = pickUpTiles.isEmpty() ? pickUp.getSpawnTiles() : pickUpTiles;
		return tile == null || on == null ? null
			: tiles.stream().min(Comparator.comparingInt(at -> at.distanceTo2D(tile) + at.distanceTo2D(on))).orElse(null);
	}

	/**
	 * A kill for a key or an item, once it lies on the ground: picked up before anything else, not another kill (the
	 * mangled bones by the barbarian just killed).
	 */
	private boolean dropToPickUp()
	{
		SubStep current = getCurrent();
		Stage stage = current == null || current.getGroundItem() >= 0 ? null
			: current.getKind() == Kind.ACT ? current.getStage() : isWalk(current) && leadsToKillForItem() ? listedAt(index + 1).getStage() : null;
		if (stage == null || stage.getKind() != Stage.Kind.KEY)
		{
			return false;
		}
		int dropped = stage.getItemIds().stream().filter(id -> !sceneObjects.groundItemTiles(id).isEmpty()).findFirst().orElse(-1);
		if (dropped < 0)
		{
			return false;
		}
		String name = stage.getItemName() != null ? stage.getItemName() : client.getItemDefinition(dropped).getName();
		return replaceCurrent(SubStep.of(Kind.ACT, "Pick up the " + name.toLowerCase(Locale.ROOT)).groundItem(dropped)
			.heldWhenDone(Set.copyOf(stage.getItemIds())).done(SubStep.Done.ITEM_HELD).build());
	}

	private static boolean isWalk(SubStep sub)
	{
		return sub.getKind() == Kind.WALK || sub.getKind() == Kind.WALK_TO_CLUE;
	}

	/** An act's ground item (king worms): outlined where it lies, the nearest as the waypoint. */
	private boolean pickUpForAct(WorldPoint tile)
	{
		SubStep current = getCurrent();
		if (current == null || current.getKind() != Kind.ACT || current.getGroundItem() < 0)
		{
			return false;
		}
		WorldPoint only = current.getGroundItemTile();
		pickUpTiles = sceneObjects.groundItemTiles(current.getGroundItem()).stream().filter(at -> only == null || only.equals(at))
			.collect(Collectors.toUnmodifiableList());
		WorldPoint nearest = nearest(tile);
		return replaceCurrent(current.toBuilder().waypoint(nearest != null ? nearest : only).build());
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

	/**
	 * The charter list's key for the port, or a destination list's for a row with no key of its own (a minecart's
	 * station), is known only once the list is open: the step's text names it from then on.
	 */
	private boolean keyFromOpenList()
	{
		SubStep current = getCurrent();
		char key = menuLines.charterKey(highlights.getMenuTarget());
		if (key == 0 && current != null && current.getRow() != null && current.getRow().getKey() == null && current.getRow().getPick() != null)
		{
			int line = menuLines.listIndex(current.getRow().getPick());
			key = line >= 0 && line < MenuLines.HOTKEYS.length() ? MenuLines.HOTKEYS.charAt(line) : 0;
		}
		if (key == 0 || current == null || current.getRow() == null || current.getText().contains(MenuLines.keyText(String.valueOf(key))))
		{
			return false;
		}
		return replaceCurrent(current.toBuilder().text(LegSteps.travelLine(current.getRow(), String.valueOf(key))).build());
	}

	/** The current step's obelisk is lit: what is left is the wait on its platform. */
	public boolean isObeliskActivated()
	{
		return obelisk.isLit();
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
		if (isTravelStep() && mayStartTravel(event) && !opensItsList(event))
		{
			travelClickTick = client.getTickCount();
		}
		obelisk.onMenuOptionClicked(event);
		SubStep current = getCurrent();
		if (event.getMenuAction().name().startsWith("GAME_OBJECT"))
		{
			wayInMemory.onObjectClicked(wayInOf(routeStep), player.getPosition().getTile(), event.getId(), event.getMenuOption());
		}
		// The object is often clicked from afar, during the walk to it: the click counts whatever the sub-step.
		if (isTaskObject(event.getId()))
		{
			taskStartedKey = routeStep.getKey();
			WorldView view = client.getTopLevelWorldView();
			taskStartedAt = view == null ? null : WorldPoint.fromScene(view, event.getParam0(), event.getParam1(), view.getPlane());
		}
		else if (isTaskCastItem(event.getItemId()) || usesTaskItem(current, event.getItemId()))
		{
			taskStartedKey = routeStep.getKey();
			taskStartedAt = player.getPosition().getTile();
		}
		if (current == null || current.getKind() != Kind.TELEPORT || current.getRow() == null)
		{
			return;
		}
		MenuTarget menuTarget = highlights.getMenuTarget();
		boolean onItem = (menuTarget.getItemIds().contains(event.getItemId())
			|| menuTarget.getItemNames().contains(MenuLabels.normalise(event.getMenuTarget())))
			&& menuLines.isTeleportClick(menuTarget, event.getMenuOption());
		boolean onObject = menuTarget.getObjectIds().contains(event.getId());
		boolean onMapButton = menuLines.isMapButton(menuTarget, event.getWidget());
		if ((onItem || onObject || onMapButton) && !current.getRow().isOpensMenu())
		{
			teleportRefusal.clicked(client.getTickCount(), player.getPosition().getTile());
		}
	}

	/** Game and MESBOX lines that may mean the game refused the current teleport or book change. */
	public void onChatMessage(ChatMessage message)
	{
		SubStep current = getCurrent();
		boolean gameLine = message.getType() == ChatMessageType.GAMEMESSAGE || message.getType() == ChatMessageType.MESBOX;
		if (gameLine)
		{
			wayInMemory.onGameLine(message.getMessage());
		}
		if (gameLine && current != null && current.getKind() == Kind.TELEPORT && current.getRow() != null)
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
			if (current != null && current.getRow() != null)
			{
				unlocks.onTeleportRefused(current.getRow());
			}
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
		steps = withRememberedWayIn(withoutRingHopsDone(deposit.before(base, tile)), step);
		index = 0;
		resetTravel();
		advance(tile);
		// A plan comes each tick while walking: the rewrites a tick makes are made again before anything is shown
		partWayIn(tile);
		dropToPickUp();
		pickUpForAct(tile);
		refreshCurrent();
		showMaking();
		updateHighlights();
		refreshObjects();
		notifyListeners();
	}

	/**
	 * While an enemy fights the player, the step's fighting act is shown whatever line is current: gear swapped to fight
	 * makes a new plan, whose lines start again at putting the step's gear on, which waits until the enemy is dead.
	 */
	private boolean refreshFight()
	{
		NPC enemy = actWatcher.getEnemy();
		SubStep act = enemy == null ? listedAt(index)
			: steps.stream().skip(index).filter(SubStep::isFights).findFirst().orElse(listedAt(index));
		SubStep view = null;
		SubStep again = null;
		if (act != null && act.isFights())
		{
			if (enemy != null)
			{
				view = SubStep.of(Kind.ACT, "Kill the " + (enemy.getName() == null ? "enemy" : enemy.getName()))
					.npcIds(Set.of(enemy.getId())).option(ATTACK).build();
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

	/** An act used only on its own tile (a drop, a feel) has its items highlighted the tick the player steps onto it. */
	private boolean refreshOnActTile(WorldPoint tile)
	{
		SubStep current = getCurrent();
		boolean on = current != null && current.getWaypoint() != null && current.getWaypoint().equals(tile);
		boolean changed = on != onActTile;
		onActTile = on;
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
			int stashId = ended.getRequirements().getStashObjectId();
			deposit.start(ended.getData().getStash().getTile(), stashId, back, client.getTickCount());
		}
	}

	/** The ended emote's items still on the player, by the names of the ones carried; none once all are put back. */
	private List<String> itemsToPutBack(Step ended)
	{
		Set<Integer> carried = player.carriedIds();
		return ended.getRequirements().getItems().stream()
			.flatMap(need -> GearActs.meetingNeed(need, carried).stream())
			.map(id -> client.getItemDefinition(id).getName())
			.collect(Collectors.toUnmodifiableList());
	}

	/** The box's left click is the place last taken from it, until a hover shows otherwise. */
	private void noteBoxUsed(SubStep done)
	{
		MenuTarget target = highlights.getMenuTarget();
		if (done.getKind() == Kind.TELEPORT && done.getRow() != null && target.getLine() == MenuLine.BOX)
		{
			leftClicks.learnBoxUsed(target.getObjectIds(), target.destination());
		}
	}

	private void noteRingHop(SubStep done)
	{
		if (done.getKind() != Kind.FAIRY_RING)
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
		return IntStream.range(from, in.size()).filter(i -> isEarlierRingHop(in.get(i))).findFirst().orElse(-1);
	}

	private boolean isEarlierRingHop(SubStep step)
	{
		return step.getKind() == Kind.FAIRY_RING && step.getGoal() == null
			&& step.getRow().getName().equals(ringChain.getLandingName());
	}

	private boolean advance(WorldPoint tile)
	{
		boolean moved = false;
		while (index < steps.size() && isComplete(steps.get(index), tile))
		{
			noteRingHop(steps.get(index));
			noteBoxUsed(steps.get(index));
			noteItemsSpent(steps.get(index));
			index++;
			resetTravel();
			moved = true;
		}
		SubStep current = listedAt(index);
		if (isStepAct(current) && routeStep != null)
		{
			actReachedStepKey = actKey(routeStep);
		}
		return moved;
	}

	/** An act of the step itself: gear put on at the bank (a lumberjack outfit) is no sign of having reached the step. */
	private static boolean isStepAct(SubStep sub)
	{
		return sub != null && sub.getKind() == Kind.ACT && !sub.isPreparation();
	}

	private boolean stillAtAct(Step step, WorldPoint tile)
	{
		return actKey(step).equals(actReachedStepKey) && tile != null && step.getTargets().stream()
			.anyMatch(target -> target.distanceTo2D(tile) <= STAY_AT_ACT_TILES && !isAllGrowing(step, target));
	}

	/** A planting place whose every patch still grows a crop: the plan has left it for another, and so does the guide. */
	private boolean isAllGrowing(Step step, WorldPoint place)
	{
		return step.getData().isPlants() && player.growingAllotments().contains(place);
	}

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

	/**
	 * Arrived where the planner can't see it: the step's NPC is in the scene a short walk away (Brimstail inside his
	 * cave, where core's tile is the entrance), or the player is beside a cache tile of the step core doesn't give. A
	 * step of several places or a probe trip never arrives this way: their NPCs and tiles belong to one stage. Nor does
	 * the NPC that only drops a key: the step is done at the object the key opens.
	 */
	private boolean arrived(Step step)
	{
		PlanStart start = routes.getStart();
		WalkCosts walk = start == null ? null : start.getWalk();
		boolean parts = step.getStages().stream().allMatch(stage -> stage.getKind() == Stage.Kind.PART);
		if (walk == null || step.getWaypointAct() != null || !parts || ClueTypes.HOT_COLD.equals(step.getType()))
		{
			return false;
		}
		List<String> npcs = step.getNpcsAtStep();
		return step.getOtherTiles().stream().anyMatch(other -> walk.costNear(other) <= ARRIVED_WALK_TILES) || !sceneObjects.npcs(npc -> npc.getName() != null && npcs.contains(npc.getName())
			&& walk.costNear(npc.getWorldLocation()) <= ARRIVED_WALK_TILES).isEmpty();
	}

	private List<SubStep> actsHere(Step step, WorldPoint tile)
	{
		SubStep stageAct = actHere(step, tile);
		return stageAct.getStage() != null ? SubSteps.stageActs(stageAct.getStage()) : subSteps.acts(step);
	}

	/**
	 * At a step of several places, the act of the stage nearest to the player. A place on another floor counts as further
	 * off, not out of reach: a tree up a ladder is still nearer than an NPC across the map.
	 */
	private static SubStep actHere(Step step, WorldPoint tile)
	{
		Stage nearest = null;
		int nearestDistance = Integer.MAX_VALUE;
		for (Stage stage : step.getStages())
		{
			for (WorldPoint at : stage.getTiles())
			{
				int floors = tile == null ? 0 : Math.abs(at.getPlane() - tile.getPlane());
				int distance = tile == null ? Integer.MAX_VALUE - 1 : at.distanceTo2D(tile) + floors * OTHER_FLOOR_TILES;
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
	/** An ended emote's STASH items are still carried, so the guide walks back to its unit to put them away. */
	public boolean owesStashPutBack()
	{
		return deposit.getAt() != null;
	}

	public boolean isAtStep()
	{
		return !steps.isEmpty() && steps.stream().allMatch(sub -> sub.getKind() == Kind.ACT);
	}

	private void clear()
	{
		route = null;
		routeStep = null;
		stepSince = ActWatcher.NEVER;
		actReachedStepKey = null;
		steps = List.of();
		index = 0;
		resetTravel();
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

	/** A new sub-step starts with no click, animation or jump seen during it. */
	private void resetTravel()
	{
		jumpedThisSubStep = false;
		teleportAnimatedTick = ActWatcher.NEVER;
		travelClickTick = ActWatcher.NEVER;
		travelStarted = false;
		ringVanished = false;
	}

	/** A teleport or book change is under way, until the player lands: its item, spell and tab are already used. */
	public boolean isTravelling()
	{
		boolean landing = !jumpedThisSubStep && teleportAnimatedTick != ActWatcher.NEVER
			&& client.getTickCount() - teleportAnimatedTick <= LANDING_TICKS;
		return isTravelAnimating() || landing;
	}

	/**
	 * A click during the travel sub-step, in the ticks before its animation would show. The click already spends the
	 * teleport (a scroll leaves the book), so a plan searched right after it is one without that teleport: held back
	 * like a plan that comes during the animation, or the guide would point at another way out until the landing.
	 */
	private boolean isTravelJustClicked()
	{
		return travelClickTick != ActWatcher.NEVER && client.getTickCount() - travelClickTick <= travelStartTicks();
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

	/**
	 * A click on the teleport's own item that only opens its list (the scroll book's Open): the hit or block animation that
	 * follows one while fighting isn't travel either, and would hide the book's outlined scroll.
	 */
	private boolean opensItsList(MenuOptionClicked event)
	{
		MenuTarget target = highlights.getMenuTarget();
		boolean onItem = target.getItemIds().contains(event.getItemId());
		return onItem && !menuLines.isTeleportClick(target, event.getMenuOption());
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

	/**
	 * A walk to NPCs ends once one of them is close by, not only at the planned tile. Any of the clue's own targets will do
	 * (waterfiends across the Ancient Cavern from the listed spawn); on a walk to a stage, one of the same kind elsewhere on
	 * the way (a werewolf before the skullball trapdoor) doesn't end it unless it is also by the walk's goal.
	 */
	private boolean stageNpcNearby(SubStep step, WorldPoint tile)
	{
		WorldPoint goal = step.getKind() == Kind.WALK_TO_CLUE || leadsToKillForItem() ? null : step.getGoal();
		return !step.getNpcIds().isEmpty() && tile != null && scene.getNpcs().stream()
			.map(NPC::getWorldLocation)
			.anyMatch(at -> at.getPlane() == tile.getPlane() && at.distanceTo2D(tile) <= SubSteps.AREA_ARRIVE_TILES
				&& (goal == null || at.distanceTo2D(goal) <= SubSteps.AREA_ARRIVE_TILES));
	}

	/** The walk leads to a kill for a key or an item: any of its NPCs close by will do, wherever the planned spot is. */
	private boolean leadsToKillForItem()
	{
		SubStep next = listedAt(index + 1);
		return next != null && next.getStage() != null && next.getStage().getKind() == Stage.Kind.KEY;
	}

	/**
	 * A walk to objects each player sees differently (Dorgesh-Kaan's broken lamps) ends once one offering the act is in the
	 * scene: the planned tile is any of them, and the act's line leads to the nearest.
	 */
	private boolean perPlayerObjectInSight(SubStep step)
	{
		return step.getOption() != null && !step.getObjectIds().isEmpty() && scene.getObjects().stream().anyMatch(leftClicks::changesForm);
	}

	/** A walk to outlined objects ends beside one of them: an allotment patch is wider than the arrival radius. */
	private boolean besideStageObject(SubStep step, WorldPoint tile)
	{
		WorldView view = client.getTopLevelWorldView();
		return !step.getObjectIds().isEmpty() && tile != null && view != null
			&& scene.getObjects().stream().anyMatch(object -> tilesFromFootprint(view, object, tile) <= 1);
	}

	private static int tilesFromFootprint(WorldView view, TileObject object, WorldPoint tile)
	{
		if (!(object instanceof GameObject))
		{
			return object.getWorldLocation().distanceTo(tile);
		}
		GameObject game = (GameObject) object;
		Point min = game.getSceneMinLocation();
		WorldPoint southWest = WorldPoint.fromScene(view, min.getX(), min.getY(), game.getPlane());
		return new WorldArea(southWest, game.sizeX(), game.sizeY()).distanceTo(tile);
	}

	private boolean isComplete(SubStep step, WorldPoint tile)
	{
		switch (step.getKind())
		{
			case WALK:
			case WALK_TO_CLUE:
				return step.getDone() == SubStep.Done.STASH_DEPOSITED && deposit.getAt() == null || PositionRules.near(tile, step.getGoal(), step.getRadius())
					|| step.getSpans().stream().anyMatch(place -> place.spans(tile)) || stageNpcNearby(step, tile)
					|| perPlayerObjectInSight(step) || besideStageObject(step, tile);
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
				// Closed without all its items, the bank is the step until the plan sends the player back to it
				return !bankMemory.isBankOpen() && player.carriedIds().containsAll(step.getBankItems())
					&& step.getCarriedAfterBank().entrySet().stream().allMatch(item -> player.carried(item.getKey()) >= item.getValue());
			case ACT:
				return isActDone(step);
			default:
				throw new IllegalArgumentException("unhandled sub-step kind " + step.getKind());
		}
	}

	private boolean isRingStep()
	{
		SubStep current = getCurrent();
		return current != null && current.getKind() == Kind.FAIRY_RING;
	}

	private boolean isVanishing()
	{
		Player local = client.getLocalPlayer();
		return local != null && local.getAnimation() == AnimationID.HUMAN_FAIRY_VANISH;
	}

	/** Any floor: Shortest Path puts some landings on the wrong one. */
	private boolean hasLanded(SubStep step, WorldPoint tile)
	{
		return jumpedThisSubStep && tile != null && tile.distanceTo2D(step.getGoal()) <= step.getRadius();
	}

	private boolean isActDone(SubStep act)
	{
		switch (act.getDone())
		{
			case WORN:
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
			case TRACK_PLAYING:
				return isPlaying(act.getTrack());
			default:
				return actWatcher.isDone(act.getDone(), stepSince);
		}
	}

	private boolean isPlaying(String track)
	{
		Widget nowPlaying = client.getWidget(InterfaceID.Music.NOW_PLAYING_TEXT);
		return nowPlaying != null && Text.removeTags(nowPlaying.getText()).trim().equalsIgnoreCase(track);
	}

	/** The scene is searched again for the new sub-step's objects and NPCs. */
	private void updateHighlights()
	{
		scene = SceneHighlights.NONE;
		highlights = stepHighlights.of(getCurrent(), routeStep);
		if (stashAhead() != StepRequirements.NO_STASH)
		{
			highlights = highlights.toBuilder().objectOption(StepHighlights.SEARCH_OPTION).build();
		}
	}

	/** Walking to the STASH unit the next act searches: it is outlined from the walk on, like a bank. */
	private int stashAhead()
	{
		SubStep current = getCurrent();
		SubStep next = listedAt(index + 1);
		return current != null && current.getKind() == Kind.WALK && next != null ? stepHighlights.stashOf(next, routeStep) : StepRequirements.NO_STASH;
	}

	private boolean refreshBox()
	{
		SubStep talk = boxTalk();
		String box = talk == null ? null : actWatcher.getBox();
		SubStep view = box == null ? null : Acts.boxView(talk, box, actWatcher.isBoxOpen(), actWatcher.isBoxSolved());
		boolean changed = !Objects.equals(view, boxView);
		boxView = view;
		return changed;
	}

	/** The talk to the step's NPC that hands over a box: the act now, or the talk before the act for the box it handed over. */
	private SubStep boxTalk()
	{
		SubStep act = listedAt(index);
		SubStep before = index > 0 ? listedAt(index - 1) : null;
		SubStep talk = before != null && before.getDone() == SubStep.Done.CHALLENGE_ITEM ? before : act;
		boolean isTalk = talk != null && talk.getKind() == Kind.ACT && talk.getActItemNamed() == null && routeStep != null
			&& talk.getText().startsWith(TALK_TO)
			&& routeStep.getNpcs().contains(talk.getText().substring(TALK_TO.length()));
		return isTalk ? talk : null;
	}

	/** A gear act listing what to put on or take off now. */
	private boolean refreshCurrent()
	{
		SubStep current = getCurrent();
		return current != null && replaceCurrent(taskActs.phased(gearActs.refresh(current, routeStep), routeStep));
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

	/** A task act shows what to do first, or the making step it is at, under the act. */
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

	/** Client thread only. A refused book change leaves the book as it was; two ticks later shows whether it changed. */
	private static final class ChangeRefusal
	{
		private static final int CHECK_TICKS = 2;

		String candidate;
		Book book;
		int checkTick;

		/** A MESBOX line during the change: the change is suspect until its check tick. */
		void suspect(String change, Book book, int tick)
		{
			candidate = change;
			this.book = book;
			checkTick = tick + CHECK_TICKS;
		}

		/** The change the game refused, once its check tick has come and the book is not the one it changes to; null otherwise. */
		String refused(int tick, int spellbookVarbit)
		{
			if (candidate == null || tick < checkTick)
			{
				return null;
			}
			String refused = spellbookVarbit != book.ordinal() ? candidate : null;
			candidate = null;
			return refused;
		}

		void clear()
		{
			candidate = null;
		}
	}

	/** The objects and NPCs found in the loaded scene for the current sub-step, searched again every few ticks. */
	@Value
	@Builder(toBuilder = true)
	private static class SceneHighlights
	{
		static final SceneHighlights NONE = SceneHighlights.builder().build();

		@Builder.Default
		List<TileObject> objects = List.of();
		@Builder.Default
		List<NPC> npcs = List.of();
		/** Outlined objects whose left click does what the step needs. */
		@Builder.Default
		List<TileObject> fillObjects = List.of();
		@Builder.Default
		List<NPC> fillNpcs = List.of();
		/** The bank booths, chests and bankers of a bank stop, filled when their left click opens the bank. */
		boolean bank;
		/** The option an outlined NPC is filled for when it is its left click (the Guardian mummy's Start-minigame). */
		String npcOption;
		/** The tick of the last search; {@link ActWatcher#NEVER} forces one. */
		@Builder.Default
		int foundTick = ActWatcher.NEVER;
		/**
		 * Searches since the last that found something: a house's furniture appears a tick or two after the load, so empty
		 * searches repeat every tick at first, then every few ticks.
		 */
		@Builder.Default
		int emptySearches = 0;

		boolean isEmpty()
		{
			return objects.isEmpty() && npcs.isEmpty();
		}
	}

	/** Client thread only. A game message right after a teleport click, with no animation, means the game refused it. */
	static final class TeleportRefusal
	{
		/** A teleport animates on its click's tick or within two: three ticks without one means it was refused. */
		private static final int CHECK_TICKS = 3;

		int clickTick = ActWatcher.NEVER;
		WorldPoint clickTile;
		String row;
		int checkTick = ActWatcher.NEVER;

		void clicked(int tick, WorldPoint tile)
		{
			clickTick = tick;
			clickTile = tile;
		}

		/** A game line within a tick of the click: the row is suspect until its check tick. */
		void onGameLine(String rowName, int tick)
		{
			if (clickTick != ActWatcher.NEVER && tick - clickTick <= 1)
			{
				row = rowName;
				checkTick = tick + CHECK_TICKS;
			}
		}

		/**
		 * The row the game refused, once its check tick has come while the step is still its teleport and the player stands
		 * by the click's tile without animating; null otherwise.
		 */
		String refused(int tick, String currentRow, WorldPoint tile, boolean standingStill)
		{
			if (row == null || tick < checkTick)
			{
				return null;
			}
			boolean sameStep = row.equals(currentRow);
			boolean stayed = tile != null && clickTile != null && !PositionRules.teleported(clickTile, tile);
			String refused = sameStep && stayed && standingStill ? row : null;
			row = null;
			clickTick = ActWatcher.NEVER;
			return refused;
		}

		void clear()
		{
			row = null;
			clickTick = ActWatcher.NEVER;
		}
	}
}
