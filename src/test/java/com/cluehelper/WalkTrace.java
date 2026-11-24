package com.cluehelper;

import com.cluehelper.guide.StepGuide;
import com.cluehelper.guide.SubStep;
import com.cluehelper.route.Leg;
import com.cluehelper.route.Route;
import com.google.gson.Gson;
import java.io.File;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Times each walk the guide points at against the route leg it was planned as: one {@link Walked} line each in
 * {@link #LOG}, listed with totals in {@link #SUMMARY}. The walk table counts a tile per half-tick, as running; a walk is
 * measured from the first game tick the player stepped to the last, so stops on the way (a closed door, a missed
 * click) count, and the time before the first step does not.
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
	private static final int RUN_ON = 1;
	private static final int ENERGY_UNITS = 100;
	private static final int NOT_PLANNED = -1;

	private final Client client;
	private final StepGuide guide;
	private final Gson gson;
	private final TimingLog timingLog;
	private SubStep walking;
	private Walked walked;
	private WorldPoint lastTile;

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
		if (walking != null && !Objects.equals(current, walking))
		{
			finishIfWalked();
		}
		if (walking == null && current != null && WALKS.contains(current.getKind()))
		{
			begin(current);
		}
		if (walking != null && tile != null && lastTile != null && !tile.equals(lastTile))
		{
			step(lastTile, tile);
		}
		lastTile = tile;
	}

	private void begin(SubStep current)
	{
		walking = current;
		walked = new Walked();
		walked.kind = current.getKind();
		walked.text = current.getText();
		walked.from = PlayerTiles.text(PlayerTiles.tile(client));
		walked.to = PlayerTiles.text(current.getGoal() != null ? current.getGoal() : current.getWaypoint());
		walked.plannedHalfTicks = plannedHalfTicks(current);
		walked.energyAtStart = client.getEnergy() / ENERGY_UNITS;
	}

	private void step(WorldPoint from, WorldPoint to)
	{
		int now = client.getTickCount();
		int tiles = from.getPlane() == to.getPlane() ? from.distanceTo2D(to) : Integer.MAX_VALUE;
		if (tiles > RUN_TILES)
		{
			walked.jumps++;
			return;
		}
		if (walked.firstStepTick == 0)
		{
			walked.firstStepTick = now;
		}
		else
		{
			walked.stoppedTicks += now - walked.lastStepTick - 1;
		}
		walked.lastStepTick = now;
		walked.steps++;
		walked.tiles += tiles;
		walked.runningSteps += client.getVarpValue(VarPlayerID.OPTION_RUN) == RUN_ON ? 1 : 0;
	}

	/** The route's walk leg ending where this walk heads, in half-ticks; {@link #NOT_PLANNED} when none does. */
	private int plannedHalfTicks(SubStep current)
	{
		Route route = guide.getRoute();
		if (route == null)
		{
			return NOT_PLANNED;
		}
		return route.getLegs().stream()
			.filter(leg -> WALK_LEGS.contains(leg.getKind()))
			.filter(leg -> leg.getTo().equals(current.getGoal()) || leg.getTo().equals(current.getWaypoint()))
			.mapToInt(Leg::getHalfTicks)
			.findFirst()
			.orElse(NOT_PLANNED);
	}

	private void finishIfWalked()
	{
		if (walked.steps > 0)
		{
			walked.ended = PlayerTiles.text(PlayerTiles.tile(client));
			walked.walkedHalfTicks = 2 * (walked.lastStepTick - walked.firstStepTick + 1);
			walked.energyAtEnd = client.getEnergy() / ENERGY_UNITS;
			String line = gson.toJson(walked);
			log.info("{} {}", TAG, line);
			timingLog.append(LOG, line, SUMMARY, lines -> tsv(lines.stream()
				.map(logged -> gson.fromJson(logged, Walked.class))
				.collect(Collectors.toList())));
		}
		walking = null;
		walked = null;
	}

	/** Every walk, then the totals over the planned ones: how far the walk table is off overall. */
	private static String tsv(List<Walked> all)
	{
		StringBuilder out = new StringBuilder("kind\ttext\tfrom\tto\tplanned_half_ticks\twalked_half_ticks\toff_by\ttiles\tsteps"
			+ "\trunning_steps\tstopped_ticks\tjumps\tenergy\n");
		for (Walked walked : all)
		{
			boolean planned = walked.plannedHalfTicks != NOT_PLANNED;
			out.append(String.join("\t", String.valueOf(walked.kind), walked.text, walked.from, walked.to,
				planned ? String.valueOf(walked.plannedHalfTicks) : "", String.valueOf(walked.walkedHalfTicks),
				planned ? String.valueOf(walked.walkedHalfTicks - walked.plannedHalfTicks) : "", String.valueOf(walked.tiles),
				String.valueOf(walked.steps), String.valueOf(walked.runningSteps), String.valueOf(walked.stoppedTicks),
				String.valueOf(walked.jumps), walked.energyAtStart + "-" + walked.energyAtEnd)).append('\n');
		}
		List<Walked> planned = all.stream().filter(walked -> walked.plannedHalfTicks != NOT_PLANNED).collect(Collectors.toList());
		int plannedTotal = planned.stream().mapToInt(walked -> walked.plannedHalfTicks).sum();
		int walkedTotal = planned.stream().mapToInt(walked -> walked.walkedHalfTicks).sum();
		out.append(String.join("\t", "TOTAL", planned.size() + " planned walks", "", "", String.valueOf(plannedTotal),
			String.valueOf(walkedTotal), String.valueOf(walkedTotal - plannedTotal))).append('\n');
		return out.toString();
	}

	/** One line of the log. */
	static final class Walked
	{
		SubStep.Kind kind;
		/** The guide's line. */
		String text;
		String from;
		String to;
		String ended;
		/** {@link #NOT_PLANNED} when no walk leg of the route ends where this walk heads. */
		int plannedHalfTicks;
		/** First to last step, two per tick. */
		int walkedHalfTicks;
		int firstStepTick;
		int lastStepTick;
		/** Ticks the player moved, and the tiles they covered: two a tick while running. */
		int steps;
		int tiles;
		int runningSteps;
		/** Ticks without a step between the first and the last. */
		int stoppedTicks;
		/** Moves too far for a step, such as a shortcut: their ticks count, their tiles don't. */
		int jumps;
		int energyAtStart;
		int energyAtEnd;
	}
}
