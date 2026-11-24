package com.cluehelper;

import com.cluehelper.guide.StepGuide;
import com.cluehelper.guide.SubStep;
import com.cluehelper.player.PositionRules;
import com.cluehelper.route.Leg;
import com.cluehelper.route.Route;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.Category;
import com.cluehelper.transport.SpellbookChange;
import com.google.gson.Gson;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Times each teleport, fairy ring and spellbook change taken while the guide points at it, against the catalog
 * Duration and menu ticks the planner priced it with: one {@link Taken} line each in {@link #LOG}, summed up per
 * catalog row in {@link #SUMMARY}. A Duration is click to control: the commit click to the landing, plus the arrival
 * lock ({@link LockWatch}), which the next click after landing tells; a landed leg waits for it while the next leg
 * starts. The commit is the last click before the travel animation that isn't a walk or a side-panel tab; a tab click
 * doesn't start a leg either. The menu ticks before the commit split into the game's share (each click to the interface
 * it opened) and the player's (the rest). A landing far from the row's destination is kept as elsewhere, not timed.
 *
 * <p>Ticks: a click carries the tick count of the period it happens in, a landing seen on GameTick (or a spellbook var)
 * the count L of the period before its boundary, so commit to landing = L + 1 - commit tick. The guide's travel-start,
 * refusal and landing windows are sampled into {@link WaitWindows} as it counts them: raw tick-count differences.
 */
@Slf4j
final class LegTimingTrace
{
	static final String TAG = "[CH-LEG]";
	static final File LOG = new File(TimingLog.DIR, "legs.jsonl");
	static final File SUMMARY = new File(TimingLog.DIR, "legs.tsv");
	private static final Set<SubStep.Kind> TIMED = EnumSet.of(SubStep.Kind.TELEPORT, SubStep.Kind.FAIRY_RING,
		SubStep.Kind.SPELLBOOK_SWAP, SubStep.Kind.CHANGE_BOOK);
	private static final Set<SubStep.Kind> CHANGES_BOOK = EnumSet.of(SubStep.Kind.SPELLBOOK_SWAP, SubStep.Kind.CHANGE_BOOK);
	private static final Set<String> NOT_A_START = Set.of(PlayerTiles.WALK, "Cancel", "Examine");
	private static final int GIVE_UP_TICKS = 40;
	private static final int LOCK_WATCH_TICKS = 15;
	/** What the planner adds for the house scene loading after a landing in it. */
	private static final int HOUSE_LOAD_TICKS = Math.max(0, WaitWindows.constant("com.cluehelper.route.TransportOptions",
		"HOUSE_LOAD_HALF_TICKS")) / 2;

	private static final int NONE = Integer.MIN_VALUE;
	/** The side panel's tab buttons: opening a tab is no step of a teleport. */
	private static final Set<Integer> SIDE_TAB_GROUPS = Set.of(InterfaceID.TOPLEVEL, InterfaceID.TOPLEVEL_OSRS_STRETCH,
		InterfaceID.TOPLEVEL_PRE_EOC);
	/** A teleport's landing tiles spread a little; farther than this, it went somewhere else. */
	private static final int LANDING_TILES = 15;

	private final Client client;
	private final StepGuide guide;
	private final Gson gson;
	private final TimingLog timingLog;
	private final WaitWindows windows;
	private final LockWatch lock;
	/** The leg clicked and not landed yet, or null. */
	private Taken taken;
	/** Where {@link #taken} should land, or null when unknown (a fairy ring's entry, a book change). */
	private WorldPoint destination;
	/** Landed legs waiting for their lock to settle. */
	private final List<Taken> settling = new ArrayList<>();
	/** The tick an interface opened after the last menu click, or {@link #NONE}. */
	private int openedAfterLastClick = NONE;
	private WorldPoint lastTile;

	LegTimingTrace(Client client, StepGuide guide, Gson gson, TimingLog timingLog, WaitWindows windows, LockWatch lock)
	{
		this.client = client;
		this.guide = guide;
		this.gson = gson;
		this.timingLog = timingLog;
		this.windows = windows;
		this.lock = lock;
	}

	@Subscribe(priority = -3)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (PlayerTiles.keypadOpen(client))
		{
			return;
		}
		String option = Text.removeTags(event.getMenuOption());
		String click = "t" + client.getTickCount() + " " + option + " " + Text.removeTags(event.getMenuTarget());
		boolean sideTab = SIDE_TAB_GROUPS.contains(event.getParam1() >>> 16);
		if (taken == null)
		{
			if (!sideTab)
			{
				beginIfGuided(option, click);
			}
			return;
		}
		taken.clicks.add(click);
		boolean travelling = taken.animatedAfterCommitTick >= taken.commitTick;
		if (sideTab || travelling || option.equals(PlayerTiles.WALK))
		{
			return;
		}
		if (openedAfterLastClick != NONE)
		{
			taken.menuGameTicks += openedAfterLastClick + 1 - taken.commitTick;
		}
		openedAfterLastClick = NONE;
		taken.commitTick = client.getTickCount();
	}

	@Subscribe(priority = -3)
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (taken != null && openedAfterLastClick == NONE)
		{
			openedAfterLastClick = client.getTickCount();
		}
	}

	@Subscribe(priority = -3)
	public void onAnimationChanged(AnimationChanged event)
	{
		if (taken != null && event.getActor() == client.getLocalPlayer() && event.getActor().getAnimation() != -1)
		{
			taken.animations.add("t" + client.getTickCount() + " " + event.getActor().getAnimation());
			if (taken.animatedAfterCommitTick < taken.commitTick)
			{
				taken.animatedAfterCommitTick = client.getTickCount();
			}
		}
	}

	@Subscribe(priority = -3)
	public void onVarbitChanged(VarbitChanged event)
	{
		if (taken != null && event.getVarbitId() == VarbitID.SPELLBOOK && taken.changesBook)
		{
			land();
		}
	}

	@Subscribe(priority = -3)
	public void onGameTick(GameTick tick)
	{
		WorldPoint tile = PlayerTiles.tile(client);
		int now = client.getTickCount();
		if (taken != null)
		{
			noteAnimating(now);
		}
		if (taken != null && !taken.changesBook && PlayerTiles.isJump(lastTile, tile))
		{
			land();
		}
		else if (taken != null && now - taken.commitTick > GIVE_UP_TICKS)
		{
			taken.outcome = Taken.Outcome.NO_LANDING;
			finish(taken, LockWatch.Lock.NONE);
			taken = null;
		}
		else if (taken != null && lastTile != null && !lastTile.equals(tile))
		{
			taken.walkedBeforeCommit = true;
		}
		settling.removeIf(landed ->
		{
			boolean settled = lock.isSettled(landed.landTick) || now - landed.landTick > LOCK_WATCH_TICKS;
			if (settled)
			{
				finish(landed, lock.settle(landed.landTick));
			}
			return settled;
		});
		lastTile = tile;
	}

	/** As the guide sees travel: the player animating on a game tick. */
	private void noteAnimating(int now)
	{
		Player local = client.getLocalPlayer();
		if (local != null && local.getAnimation() != -1)
		{
			taken.firstAnimatingTick = taken.firstAnimatingTick == 0 ? now : taken.firstAnimatingTick;
			taken.lastAnimatingTick = now;
		}
	}

	private void beginIfGuided(String option, String click)
	{
		SubStep current = guide.getCurrent();
		if (current != null && TIMED.contains(current.getKind()) && !NOT_A_START.contains(option))
		{
			begin(current, click);
		}
	}

	private void begin(SubStep current, String click)
	{
		taken = new Taken();
		taken.kind = current.getKind();
		taken.changesBook = CHANGES_BOOK.contains(current.getKind());
		taken.firstTick = client.getTickCount();
		taken.commitTick = taken.firstTick;
		taken.clicks.add(click);
		taken.from = PlayerTiles.text(PlayerTiles.tile(client));
		openedAfterLastClick = NONE;
		describePlanned(current);
	}

	private void describePlanned(SubStep current)
	{
		CatalogRow row = current.getRow();
		SpellbookChange change = current.getChange();
		destination = null;
		if (taken.changesBook && change != null)
		{
			taken.name = change.getName();
			taken.plannedDuration = change.getDuration();
			taken.plannedMenuTicks = change.getMenuTicks();
			taken.temporary = change.isTemporary();
		}
		else if (row != null)
		{
			taken.name = row.getName();
			taken.category = String.valueOf(row.getCategory());
			taken.destination = PlayerTiles.text(row.getDestination());
			taken.plannedDuration = row.getDuration();
			taken.plannedMenuTicks = row.getMenuTicks();
			taken.plannedHouseLoad = PositionRules.POH_NODE.equals(row.getDestination()) ? HOUSE_LOAD_TICKS : 0;
			destination = PositionRules.POH_NODE.equals(row.getDestination()) ? null : row.getDestination();
		}
		taken.plannedLegTicks = plannedLeg(row, change);
	}

	/** The guided route's leg for this row or change, in ticks with its extras (a book stop, a house walk); 0 if none. */
	private int plannedLeg(CatalogRow row, SpellbookChange change)
	{
		Route route = guide.getRoute();
		if (route == null)
		{
			return 0;
		}
		return route.getLegs().stream()
			.filter(leg -> row != null && (leg.getRow() == row || leg.getLanding() == row) || change != null && leg.getChange() == change)
			.mapToInt(Leg::getTicks)
			.findFirst()
			.orElse(0);
	}

	private void land()
	{
		taken.outcome = Taken.Outcome.LANDED;
		taken.landTick = client.getTickCount();
		taken.commitToLand = taken.landTick + 1 - taken.commitTick;
		taken.menuTicks = taken.commitTick - taken.firstTick;
		taken.menuPlayerTicks = taken.menuTicks - taken.menuGameTicks;
		WorldPoint landed = PlayerTiles.tile(client);
		taken.landedAt = PlayerTiles.text(landed);
		if (isElsewhere(landed))
		{
			taken.outcome = Taken.Outcome.ELSEWHERE;
			finish(taken, LockWatch.Lock.NONE);
			taken = null;
			return;
		}
		sampleWindows();
		if (taken.changesBook)
		{
			finish(taken, LockWatch.Lock.NONE);
		}
		else
		{
			settling.add(taken);
		}
		taken = null;
	}

	/** Landed far from the row's destination: another teleport, or a house instance's tiles shifting as it loads. */
	private boolean isElsewhere(WorldPoint landed)
	{
		return destination != null && landed != null
			&& (landed.getPlane() != destination.getPlane() || landed.distanceTo2D(destination) > LANDING_TILES);
	}

	private void sampleWindows()
	{
		String detail = taken.name;
		if (taken.changesBook)
		{
			windows.sample(WaitWindows.Window.CHANGE_REFUSAL, taken.landTick - taken.commitTick, detail);
			return;
		}
		boolean minigame = Category.MINIGAME.name().equals(taken.category);
		if (taken.animatedAfterCommitTick >= taken.commitTick)
		{
			int toAnimation = taken.animatedAfterCommitTick - taken.commitTick;
			windows.sample(minigame ? WaitWindows.Window.MINIGAME_TRAVEL_START : WaitWindows.Window.TRAVEL_START, toAnimation, detail);
			if (!minigame)
			{
				windows.sample(WaitWindows.Window.TELEPORT_REFUSAL, toAnimation, detail);
			}
		}
		if (taken.lastAnimatingTick != 0)
		{
			windows.sample(WaitWindows.Window.LANDING, taken.landTick - taken.lastAnimatingTick, detail);
		}
		if (Category.WILDERNESS_OBELISK.name().equals(taken.category) && taken.firstAnimatingTick != 0)
		{
			windows.sample(WaitWindows.Window.OBELISK_LANDING, taken.landTick - taken.firstAnimatingTick, detail);
		}
	}

	private void finish(Taken done, LockWatch.Lock settled)
	{
		done.lockAtLeast = settled.atLeast;
		done.lockAtMost = settled.atMost;
		done.lockTakenBy = settled.takenBy;
		String line = gson.toJson(done);
		log.info("{} {}", TAG, line);
		timingLog.append(LOG, line, SUMMARY, lines -> LegTimings.tsv(lines.stream()
			.map(logged -> gson.fromJson(logged, Taken.class))
			.collect(Collectors.toList())));
	}

	/** One line of the log. */
	static final class Taken
	{
		enum Outcome
		{
			LANDED,
			/** Landed far from the row's destination: not this teleport's timing. */
			ELSEWHERE,
			/** Refused, cancelled, or the player did something else. */
			NO_LANDING
		}

		SubStep.Kind kind;
		String name;
		String category;
		String destination;
		boolean changesBook;
		/** A Spellbook Swap, which the planner prices with its own fixed cost. */
		boolean temporary;
		int plannedDuration;
		int plannedMenuTicks;
		/** The house scene's load, which the planner adds to a landing in the house. */
		int plannedHouseLoad;
		int plannedLegTicks;
		Outcome outcome;
		List<String> clicks = new ArrayList<>();
		int firstTick;
		int commitTick;
		/** First click to the commit click: the menu ticks, unless the player walked in between. */
		int menuTicks;
		/** The menu ticks the game took: each click before the commit to the interface it opened. */
		int menuGameTicks;
		/** The rest of the menu ticks: the player reading and clicking. */
		int menuPlayerTicks;
		/** The player walked between the first click and the commit, so {@link #menuTicks} holds a walk. */
		boolean walkedBeforeCommit;
		int landTick;
		int commitToLand;
		int lockAtLeast;
		/** {@link LockWatch#UNKNOWN} when no click after landing was seen taken. */
		int lockAtMost = LockWatch.UNKNOWN;
		/** The click after landing that was taken, and the effect that showed it. */
		String lockTakenBy;
		String from;
		String landedAt;
		List<String> animations = new ArrayList<>();
		/** The first animation since the commit click; older than the commit while none came. */
		int animatedAfterCommitTick = Integer.MIN_VALUE;
		/** The first and last game ticks the player was seen animating, before landing; 0 when never. */
		int firstAnimatingTick;
		int lastAnimatingTick;
	}
}
