package com.cluehelper.guide;

import com.cluehelper.link.ClueLink;
import com.cluehelper.player.Book;
import com.cluehelper.player.Container;
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
import com.cluehelper.transport.RowAvailability;
import com.cluehelper.transport.SpellbookChange;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.InterfaceID;
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
	/** The spell object's param holding its spellbook widget id (B08). */
	private static final int PARAM_SPELL_WIDGET = 596;
	private static final String CAPE_LIMIT_TEXT = "five times per day";
	private static final int NO_ANIMATION = -1;
	/**
	 * Once the act is reached, it stays while the player is this close to a target on any plane: core's tile can
	 * be on another floor than its NPC (Donovan stands upstairs, his tile is on the ground floor).
	 */
	static final int STAY_AT_ACT_TILES = 20;

	private final Client client;
	private final ClueLink clueLink;
	private final RoutePlanner routes;
	private final PlayerModel player;
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
	private int highlightSpellWidget = -1;
	private boolean highlightWorn;
	private int highlightMenuWidget = ScrollBook.NO_WIDGET;
	private Tab highlightTab = Tab.NONE;

	@Inject
	StepGuide(Client client, ClueLink clueLink, RoutePlanner routes, PlayerModel player)
	{
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

	public List<SubStep> getSteps()
	{
		return steps;
	}

	/** Item ids to outline in the inventory and equipment for the current sub-step. */
	public Set<Integer> getHighlightItems()
	{
		return highlightItems;
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
		if (changed)
		{
			updateHighlights();
			notifyListeners();
		}
	}

	/** MESBOX lines during a book change are the refusal backstop (PLAN §5.4 Consumption, B04). */
	public void onChatMessage(ChatMessage message)
	{
		SubStep current = getCurrent();
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
			: SubSteps.of(committed, step);
		index = 0;
		jumpedThisSubStep = false;
		advance(tile);
		updateHighlights();
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
		highlightSpellWidget = -1;
		highlightMenuWidget = ScrollBook.NO_WIDGET;
		highlightTab = Tab.NONE;
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
				return client.getWidget(InterfaceID.Bankmain.UNIVERSE) != null;
			default:
				return false;
		}
	}

	private void updateHighlights()
	{
		SubStep current = getCurrent();
		highlightItems = Set.of();
		highlightSpellWidget = -1;
		highlightWorn = false;
		highlightMenuWidget = ScrollBook.NO_WIDGET;
		highlightTab = Tab.NONE;
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
		else if (current.getKind() == SubStep.Kind.TELEPORT && current.getRow().getCategory() != Category.MINIGAME)
		{
			highlightHeld(current.getRow());
			highlightMenuWidget = ScrollBook.entry(current.getRow().getName());
		}
		else if (current.getKind() == SubStep.Kind.CHANGE_BOOK && current.getChange().isAnywhere())
		{
			highlightHeld(asRow(current.getChange()));
		}
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
