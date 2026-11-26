package com.cluehelper;

import com.cluehelper.guide.StepGuide;
import com.cluehelper.guide.SubStep;
import com.cluehelper.route.Leg;
import com.cluehelper.route.Route;
import com.google.gson.Gson;
import java.io.File;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Times each walk the guide points at against the route leg it was planned as: one {@link Walked} line each in
 * {@link #LOG}, listed with totals in {@link #SUMMARY}. The walk table counts a tile per half-tick, as running, with the
 * doors and shortcuts on the way priced in. A walk runs from the first step to arriving on its leg's end tile, however
 * soon the guide moves on; a jump before the first step (the landing it starts from) is not part of it, one after is a
 * crossing on the way and is. A walk that stops short of its end is kept but left out of the totals.
 */
@Slf4j
final class WalkTrace
{
	static final String TAG = "[CH-WALK]";
	static final File LOG = new File(TimingLog.DIR, "walks.jsonl");
	static final File SUMMARY = new File(TimingLog.DIR, "walks.tsv");
	private static final Set<SubStep.Kind> WALKS = EnumSet.of(SubStep.Kind.WALK, SubStep.Kind.WALK_TO_CLUE, SubStep.Kind.WILDERNESS_EXIT);
	private static final Set<Leg.Kind> WALK_LEGS = EnumSet.of(Leg.Kind.WALK, Leg.Kind.WILDERNESS_EXIT);
	/** A running step covers at most this many tiles; anything farther is a crossing or a teleport, not walked. */
	private static final int RUN_TILES = 2;
	/** Arrived: standing on the end tile or beside it, as a walk to an object or NPC ends. */
	private static final int ARRIVED_TILES = 1;
	/** A walk's leg starts this close to where the player is when it does. */
	private static final int LEG_START_TILES = 10;
	/** Standing this long, not animating, short of the end: the walk stopped there. */
	private static final int STOPPED_TICKS = 3;
	private static final int GIVE_UP_TICKS = 300;
	private static final int RUN_ON = 1;
	private static final int ENERGY_UNITS = 100;
	private static final int NOT_PLANNED = -1;

	private final Client client;
	private final StepGuide guide;
	private final Gson gson;
	private final TimingLog timingLog;
	/** The walk sub-step being measured, or the last one measured, so it starts once. */
	private SubStep walkStep;
	private Walked walked;
	private WorldPoint end;
	private WorldPoint lastTile;
	private int standingTicks;

	WalkTrace(Client client, StepGuide guide, Gson gson, TimingLog timingLog)
	{
		this.client = client;
		this.guide = guide;
		this.gson = gson;
		this.timingLog = timingLog;
	}

	@Subscribe(priority = -3)
	public void onGameTick(GameTick tick)
	{
		SubStep current = guide.getCurrent();
		WorldPoint tile = PlayerTiles.tile(client);
		if (walked == null && current != null && WALKS.contains(current.getKind()) && !current.equals(walkStep))
		{
			begin(current, tile);
		}
		if (walked != null && tile != null)
		{
			follow(tile);
		}
		lastTile = tile;
	}

	private void begin(SubStep current, WorldPoint tile)
	{
		walkStep = current;
		walked = new Walked();
		walked.kind = current.getKind();
		walked.text = current.getText();
		walked.from = PlayerTiles.text(tile);
		Leg leg = plannedLeg(current, tile);
		end = leg != null ? leg.getTo() : current.getGoal() != null ? current.getGoal() : current.getWaypoint();
		walked.to = PlayerTiles.text(end);
		walked.plannedHalfTicks = leg == null ? NOT_PLANNED : leg.getHalfTicks();
		walked.energyAtStart = client.getEnergy() / ENERGY_UNITS;
		walked.startTick = client.getTickCount();
		standingTicks = 0;
	}

	private void follow(WorldPoint tile)
	{
		boolean moved = lastTile != null && !tile.equals(lastTile);
		if (moved)
		{
			step(lastTile, tile);
		}
		standingTicks = moved || isAnimating() ? 0 : standingTicks + 1;
		int now = client.getTickCount();
		if (walked.steps > 0 && end != null && tile.getPlane() == end.getPlane() && tile.distanceTo2D(end) <= ARRIVED_TILES)
		{
			finish(Walked.Outcome.ARRIVED);
		}
		else if (walked.steps > 0 && standingTicks >= STOPPED_TICKS)
		{
			finish(Walked.Outcome.STOPPED);
		}
		else if (now - walked.startTick > GIVE_UP_TICKS)
		{
			finish(Walked.Outcome.STOPPED);
		}
	}

	private void step(WorldPoint from, WorldPoint to)
	{
		int now = client.getTickCount();
		int tiles = from.getPlane() == to.getPlane() ? from.distanceTo2D(to) : Integer.MAX_VALUE;
		if (tiles > RUN_TILES && walked.steps == 0)
		{
			walked.from = PlayerTiles.text(to);
			return;
		}
		if (tiles > RUN_TILES)
		{
			walked.jumps++;
		}
		else
		{
			walked.tiles += tiles;
			walked.runningSteps += client.getVarpValue(VarPlayerID.OPTION_RUN) == RUN_ON ? 1 : 0;
		}
		if (walked.steps > 0)
		{
			walked.stoppedTicks += now - walked.lastStepTick - 1;
		}
		walked.firstStepTick = walked.steps == 0 ? now : walked.firstStepTick;
		walked.lastStepTick = now;
		walked.steps++;
	}

	private boolean isAnimating()
	{
		Player local = client.getLocalPlayer();
		return local != null && local.getAnimation() != -1;
	}

	/**
	 * The route's walk leg for this walk: the one ending where it heads, else the one starting nearest the player (a walk
	 * to an object ends beside it, not on the sub-step's tile); null when none starts close.
	 */
	private Leg plannedLeg(SubStep current, WorldPoint at)
	{
		Route route = guide.getRoute();
		if (route == null || at == null)
		{
			return null;
		}
		List<Leg> walkLegs = route.getLegs().stream().filter(leg -> WALK_LEGS.contains(leg.getKind())).collect(Collectors.toList());
		return walkLegs.stream()
			.filter(leg -> leg.getTo().equals(current.getGoal()) || leg.getTo().equals(current.getWaypoint()))
			.findFirst()
			.orElseGet(() -> walkLegs.stream()
				.filter(leg -> leg.getFrom().getPlane() == at.getPlane() && leg.getFrom().distanceTo2D(at) <= LEG_START_TILES)
				.min(Comparator.comparingInt(leg -> leg.getFrom().distanceTo2D(at)))
				.orElse(null));
	}

	private void finish(Walked.Outcome outcome)
	{
		walked.outcome = outcome;
		walked.ended = PlayerTiles.text(PlayerTiles.tile(client));
		walked.walkedHalfTicks = 2 * (walked.lastStepTick - walked.firstStepTick + 1);
		walked.energyAtEnd = client.getEnergy() / ENERGY_UNITS;
		String line = gson.toJson(walked);
		log.info("{} {}", TAG, line);
		timingLog.append(LOG, line, SUMMARY, lines -> tsv(lines.stream()
			.map(logged -> gson.fromJson(logged, Walked.class))
			.collect(Collectors.toList())));
		walked = null;
	}

	/** Every walk, then the totals over the planned ones that arrived: how far the walk table is off overall. */
	private static String tsv(List<Walked> all)
	{
		StringBuilder out = new StringBuilder("kind\ttext\toutcome\tfrom\tto\tended\tplanned_half_ticks\twalked_half_ticks\toff_by"
			+ "\ttiles\tsteps\trunning_steps\tstopped_ticks\tjumps\tenergy\n");
		for (Walked walked : all)
		{
			boolean planned = walked.plannedHalfTicks != NOT_PLANNED;
			out.append(String.join("\t", String.valueOf(walked.kind), walked.text, String.valueOf(walked.outcome), walked.from,
				walked.to, walked.ended, planned ? String.valueOf(walked.plannedHalfTicks) : "", String.valueOf(walked.walkedHalfTicks),
				planned ? String.valueOf(walked.walkedHalfTicks - walked.plannedHalfTicks) : "", String.valueOf(walked.tiles),
				String.valueOf(walked.steps), String.valueOf(walked.runningSteps), String.valueOf(walked.stoppedTicks),
				String.valueOf(walked.jumps), walked.energyAtStart + "-" + walked.energyAtEnd)).append('\n');
		}
		List<Walked> comparable = all.stream()
			.filter(walked -> walked.plannedHalfTicks != NOT_PLANNED && walked.outcome == Walked.Outcome.ARRIVED)
			.collect(Collectors.toList());
		int plannedTotal = comparable.stream().mapToInt(walked -> walked.plannedHalfTicks).sum();
		int walkedTotal = comparable.stream().mapToInt(walked -> walked.walkedHalfTicks).sum();
		out.append(String.join("\t", "TOTAL", comparable.size() + " planned walks that arrived", "", "", "", "",
			String.valueOf(plannedTotal), String.valueOf(walkedTotal), String.valueOf(walkedTotal - plannedTotal))).append('\n');
		out.append(String.join("\t", "VERDICT", "median walk against its plan, in half-ticks",
			TimingLog.verdict(comparable.stream().mapToLong(walked -> walked.walkedHalfTicks - walked.plannedHalfTicks), 0))).append('\n');
		return out.toString();
	}

	/** One line of the log. */
	static final class Walked
	{
		enum Outcome
		{
			ARRIVED,
			/** Stood still short of the end, or took too long: not comparable with its plan. */
			STOPPED
		}

		SubStep.Kind kind;
		/** The guide's line. */
		String text;
		Outcome outcome;
		String from;
		/** The leg's end tile, or the sub-step's when no leg matched. */
		String to;
		String ended;
		/** {@link #NOT_PLANNED} when no walk leg of the route matched. */
		int plannedHalfTicks;
		/** First to last step, two per tick. */
		int walkedHalfTicks;
		int startTick;
		int firstStepTick;
		int lastStepTick;
		/** Ticks the player moved, and the tiles they covered: two a tick while running. */
		int steps;
		int tiles;
		int runningSteps;
		/** Ticks without a step between the first and the last: waiting at a door, an obstacle's animation. */
		int stoppedTicks;
		/** Doors, shortcuts and stairs on the way, too far for a step: their ticks count, their tiles don't. */
		int jumps;
		int energyAtStart;
		int energyAtEnd;
	}
}
