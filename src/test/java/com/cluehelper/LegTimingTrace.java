package com.cluehelper;

import com.cluehelper.guide.StepGuide;
import com.cluehelper.guide.SubStep;
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
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Times each teleport, fairy ring and spellbook change taken while the guide points at it, against the catalog
 * Duration and menu ticks the planner priced it with: one {@link Taken} line each in {@link #LOG}, summed up per
 * catalog row in {@link #SUMMARY}. A Duration is click to control: the commit click to the landing, plus the arrival
 * lock ({@link LockWatch}). Walk-click a few times right after landing to pin the lock down.
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

	private enum Phase
	{
		IDLE,
		/** Clicked on the guided leg, not landed yet. */
		CLICKED,
		/** Landed; waiting for a walk click that moves the player. */
		LANDED
	}

	private final Client client;
	private final StepGuide guide;
	private final Gson gson;
	private final TimingLog timingLog;
	private final WaitWindows windows;
	private final LockWatch lock = new LockWatch();
	private Phase phase = Phase.IDLE;
	private Taken taken;
	private WorldPoint lastTile;

	LegTimingTrace(Client client, StepGuide guide, Gson gson, TimingLog timingLog, WaitWindows windows)
	{
		this.client = client;
		this.guide = guide;
		this.gson = gson;
		this.timingLog = timingLog;
		this.windows = windows;
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
		switch (phase)
		{
			case IDLE:
				beginIfGuided(option, click);
				break;
			case CLICKED:
				taken.clicks.add(click);
				taken.commitTick = client.getTickCount();
				break;
			case LANDED:
				if (option.equals(PlayerTiles.WALK))
				{
					lock.onWalkClick(client.getTickCount());
				}
				else
				{
					finish(lock.settle(PlayerTiles.tile(client)));
					beginIfGuided(option, click);
				}
				break;
		}
	}

	@Subscribe(priority = -3)
	public void onAnimationChanged(AnimationChanged event)
	{
		if (phase != Phase.IDLE && event.getActor() == client.getLocalPlayer() && event.getActor().getAnimation() != -1)
		{
			taken.animations.add("t" + client.getTickCount() + " " + event.getActor().getAnimation());
			if (phase == Phase.CLICKED && taken.animatedAfterCommitTick < taken.commitTick)
			{
				taken.animatedAfterCommitTick = client.getTickCount();
			}
		}
	}

	@Subscribe(priority = -3)
	public void onVarbitChanged(VarbitChanged event)
	{
		if (phase == Phase.CLICKED && event.getVarbitId() == VarbitID.SPELLBOOK && taken.changesBook)
		{
			land();
			finish(LockWatch.Lock.NONE);
		}
	}

	@Subscribe(priority = -3)
	public void onGameTick(GameTick tick)
	{
		lock.onGameTick();
		WorldPoint tile = PlayerTiles.tile(client);
		int now = client.getTickCount();
		if (phase == Phase.CLICKED)
		{
			noteAnimating(now);
		}
		if (phase == Phase.CLICKED && !taken.changesBook && PlayerTiles.isJump(lastTile, tile))
		{
			land();
		}
		else if (phase == Phase.CLICKED && now - taken.commitTick > GIVE_UP_TICKS)
		{
			taken.outcome = Taken.Outcome.NO_LANDING;
			finish(LockWatch.Lock.NONE);
		}
		else if (phase == Phase.LANDED && (lock.hasMoved(tile) || now - taken.landTick > LOCK_WATCH_TICKS))
		{
			finish(lock.settle(tile));
		}
		else if (phase == Phase.CLICKED && lastTile != null && !lastTile.equals(tile))
		{
			taken.walkedBeforeCommit = true;
		}
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
		describePlanned(current);
		phase = Phase.CLICKED;
	}

	private void describePlanned(SubStep current)
	{
		CatalogRow row = current.getRow();
		SpellbookChange change = current.getChange();
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
		WorldPoint landed = PlayerTiles.tile(client);
		taken.landedAt = PlayerTiles.text(landed);
		lock.arrived(taken.landTick, landed);
		phase = Phase.LANDED;
		sampleWindows();
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

	private void finish(LockWatch.Lock settled)
	{
		taken.lockAtLeast = settled.atLeast;
		taken.lockAtMost = settled.atMost;
		String line = gson.toJson(taken);
		log.info("{} {}", TAG, line);
		timingLog.append(LOG, line, SUMMARY, lines -> LegTimings.tsv(lines.stream()
			.map(logged -> gson.fromJson(logged, Taken.class))
			.collect(Collectors.toList())));
		taken = null;
		phase = Phase.IDLE;
	}

	/** One line of the log. */
	static final class Taken
	{
		enum Outcome
		{
			LANDED,
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
		int plannedLegTicks;
		Outcome outcome;
		List<String> clicks = new ArrayList<>();
		int firstTick;
		int commitTick;
		/** First click to the commit click: the menu ticks, unless the player walked in between. */
		int menuTicks;
		/** The player walked between the first click and the commit, so {@link #menuTicks} holds a walk. */
		boolean walkedBeforeCommit;
		int landTick;
		int commitToLand;
		int lockAtLeast;
		/** {@link LockWatch#UNKNOWN} when no accepted walk click told it. */
		int lockAtMost = LockWatch.UNKNOWN;
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
