package com.cluehelper;

import com.google.gson.Gson;
import java.io.File;
import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * How long the events the guide waits for really take, against the window it waits: one {@link Sample} line each in
 * {@link #LOG}, summed up per window in {@link #SUMMARY}. Samples are raw tick-count differences, as the guide itself
 * subtracts them, so they compare straight with its constant. Each window's value is read from its own constant, so it
 * is never restated here.
 */
@Slf4j
final class WaitWindows
{
	static final String TAG = "[CH-WINDOW]";
	static final File LOG = new File(TimingLog.DIR, "wait-windows.jsonl");
	static final File SUMMARY = new File(TimingLog.DIR, "wait-windows.tsv");
	private static final int UNREAD = -1;

	/** Whether the guide still accepts an event that comes exactly at its window's value. */
	enum Bound
	{
		UP_TO,
		BELOW
	}

	enum Window
	{
		/** Teleport click to its animation. */
		TRAVEL_START("com.cluehelper.guide.StepGuide", "TRAVEL_START_TICKS", Bound.UP_TO),
		/** Grouping teleport click to its animation. */
		MINIGAME_TRAVEL_START("com.cluehelper.guide.StepGuide", "MINIGAME_TRAVEL_START_TICKS", Bound.UP_TO),
		/** Teleport click to its animation; later, the click counts as refused. */
		TELEPORT_REFUSAL("com.cluehelper.guide.StepGuide$TeleportRefusal", "CHECK_TICKS", Bound.BELOW),
		/** The travel animation's last tick to the landing. */
		LANDING("com.cluehelper.guide.StepGuide", "LANDING_TICKS", Bound.UP_TO),
		/** The obelisk portal animation's start to the landing. */
		OBELISK_LANDING("com.cluehelper.guide.ObeliskDestination", "LANDING_TICKS", Bound.UP_TO),
		/** Spellbook change click to the book var changing. */
		CHANGE_REFUSAL("com.cluehelper.guide.StepGuide$ChangeRefusal", "CHECK_TICKS", Bound.UP_TO),
		/** Click on a door, gate or shortcut to the player's first step. */
		CROSSING_START("com.cluehelper.ui.WaypointOverlay", "CROSSING_START_TICKS", Bound.UP_TO),
		/** Click on a door, gate or shortcut to standing on its far side. */
		CROSSING("com.cluehelper.ui.WaypointOverlay", "CROSSING_TICKS", Bound.UP_TO),
		/** Click on the step's way in to the guide moving on from it: the ride's whole length. */
		ENTERING("com.cluehelper.ui.WaypointOverlay", "ENTERING_TICKS", Bound.UP_TO),
		/** An object or NPC the guide targets appearing to the guide outlining it. */
		OBJECT_REFRESH("com.cluehelper.guide.StepGuide", "OBJECT_REFRESH_TICKS", Bound.UP_TO),
		/** STASH Search click to its withdraw or deposit line. */
		STASH_LINE("com.cluehelper.memory.StashMemory", "SEARCH_LINE_TICKS", Bound.UP_TO),
		/** Check click to the charge line. */
		CHECK_LINE("com.cluehelper.player.CheckLines", "LINE_TICKS", Bound.UP_TO),
		/** Seeing the player in a house to the last furniture object loading. */
		HOUSE_SCAN("com.cluehelper.poh.PohService", "SCAN_DELAY_TICKS", Bound.UP_TO),
		/** The master clue leaving the inventory to its ground item showing. */
		CLUE_DROP("com.cluehelper.link.ThreeStepSwap", "DROP_TICKS", Bound.BELOW),
		/** Deposit click in the bank to the bank's contents updating. */
		BANK_DEPOSIT("com.cluehelper.memory.BankMemory$LateDeposits", "DEPOSIT_TICKS", Bound.UP_TO),
		/** The inventory updating after a deposit to the bank's contents updating. */
		BANK_CONFIRM("com.cluehelper.memory.BankMemory$LateDeposits", "CONFIRM_TICKS", Bound.UP_TO);

		private final String owner;
		private final String constant;
		private final Bound bound;

		Window(String owner, String constant, Bound bound)
		{
			this.owner = owner;
			this.constant = constant;
			this.bound = bound;
		}

		boolean fits(int ticks, int windowTicks)
		{
			return bound == Bound.UP_TO ? ticks <= windowTicks : ticks < windowTicks;
		}
	}

	private final Gson gson;
	private final TimingLog timingLog;
	private final Map<Window, Integer> windowTicks = new EnumMap<>(Window.class);

	WaitWindows(Gson gson, TimingLog timingLog)
	{
		this.gson = gson;
		this.timingLog = timingLog;
		for (Window window : Window.values())
		{
			windowTicks.put(window, read(window));
		}
	}

	void sample(Window window, int ticks, String detail)
	{
		Sample sample = new Sample();
		sample.window = window;
		sample.ticks = ticks;
		sample.detail = detail;
		String line = gson.toJson(sample);
		log.info("{} {}", TAG, line);
		timingLog.append(LOG, line, SUMMARY, lines -> tsv(lines.stream()
			.map(logged -> gson.fromJson(logged, Sample.class))
			.collect(Collectors.toList())));
	}

	/** The guide's constant, or {@link #UNREAD} (and a warning) when it was renamed or moved. */
	private static int read(Window window)
	{
		try
		{
			Field field = Class.forName(window.owner).getDeclaredField(window.constant);
			field.setAccessible(true);
			return field.getInt(null);
		}
		catch (ReflectiveOperationException e)
		{
			log.warn("{} can't read {}.{}: update WaitWindows", TAG, window.owner, window.constant, e);
			return UNREAD;
		}
	}

	private String tsv(List<Sample> all)
	{
		StringBuilder out = new StringBuilder("window\tconstant\tguide_waits\tbound\tsamples\tticks\toutside\n");
		Map<Window, List<Sample>> byWindow = all.stream()
			.collect(Collectors.groupingBy(sample -> sample.window, () -> new EnumMap<>(Window.class), Collectors.toList()));
		byWindow.forEach((window, samples) ->
		{
			int waits = windowTicks.get(window);
			long outside = samples.stream().filter(sample -> !window.fits(sample.ticks, waits)).count();
			out.append(String.join("\t", window.name(), window.owner + "." + window.constant, String.valueOf(waits),
				window.bound.name(), String.valueOf(samples.size()), TimingLog.range(samples.stream().mapToLong(sample -> sample.ticks)),
				String.valueOf(outside))).append('\n');
		});
		return out.toString();
	}

	/** One line of the log. */
	static final class Sample
	{
		Window window;
		/** The raw tick-count difference. */
		int ticks;
		/** What was clicked or crossed, for a sample outside its window. */
		String detail;
	}
}
