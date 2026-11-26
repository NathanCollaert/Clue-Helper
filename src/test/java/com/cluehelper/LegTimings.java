package com.cluehelper;

import com.cluehelper.LegTimingTrace.Taken;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One row per catalog row or spellbook change: what the planner priced against what was measured, the game's time apart
 * from the player's.
 *
 * <p>Game: the commit click to the landing, plus the arrival lock's lower bound (ticks the game took no click). That is
 * what the catalog Duration prices (with the house load for a landing in the house), and its verdict judges the median
 * only once {@link TimingLog.Batch#ENOUGH} samples landed. Player: the menu ticks split into the game's share (each click
 * to the interface it opened) and the player's (reading and clicking); the catalog's menu ticks price both, so that
 * verdict judges their sum. The lock's upper bound is when the player clicked again, which says more about the player.
 */
final class LegTimings
{
	private static final String HEADER = String.join("\t", "kind", "name", "destination", "category", "landed", "elsewhere",
		"not_landed", "planned_duration", "planned_house_load", "game_commit_to_land", "game_lock_at_least", "game_median",
		"duration_verdict", "planned_menu_ticks", "menu_game_ticks", "menu_player_ticks", "menu_median", "menu_verdict",
		"player_lock_at_most", "lock_taken_by", "planned_leg_ticks");

	private LegTimings()
	{
	}

	static String tsv(List<Taken> all)
	{
		Map<String, List<Taken>> byRow = all.stream().collect(Collectors.groupingBy(
			taken -> taken.kind + "\t" + taken.name + "\t" + taken.destination, TreeMap::new, Collectors.toList()));
		StringBuilder out = new StringBuilder(HEADER).append('\n');
		byRow.forEach((key, samples) -> out.append(row(key, samples)).append('\n'));
		return out.toString();
	}

	private static String row(String kindNameDestination, List<Taken> samples)
	{
		Taken latest = samples.get(samples.size() - 1);
		List<Taken> landed = samples.stream().filter(taken -> taken.outcome == Taken.Outcome.LANDED).collect(Collectors.toList());
		List<Taken> menus = landed.stream().filter(taken -> !taken.walkedBeforeCommit).collect(Collectors.toList());
		return String.join("\t", kindNameDestination, String.valueOf(latest.category), String.valueOf(landed.size()),
			String.valueOf(count(samples, Taken.Outcome.ELSEWHERE)), String.valueOf(count(samples, Taken.Outcome.NO_LANDING)),
			String.valueOf(latest.plannedDuration), String.valueOf(latest.plannedHouseLoad),
			TimingLog.range(landed.stream().mapToLong(taken -> taken.commitToLand)),
			TimingLog.range(landed.stream().mapToLong(taken -> taken.lockAtLeast)),
			TimingLog.median(landed.stream().mapToLong(LegTimings::gameTicks)),
			TimingLog.verdict(landed.stream().mapToLong(LegTimings::gameTicks), latest.plannedDuration + latest.plannedHouseLoad),
			String.valueOf(latest.plannedMenuTicks),
			TimingLog.range(menus.stream().mapToLong(taken -> taken.menuGameTicks)),
			TimingLog.range(menus.stream().mapToLong(taken -> taken.menuPlayerTicks)),
			TimingLog.median(menus.stream().mapToLong(taken -> taken.menuTicks)),
			TimingLog.verdict(menus.stream().mapToLong(taken -> taken.menuTicks), latest.plannedMenuTicks),
			TimingLog.range(landed.stream().filter(taken -> taken.lockAtMost != LockWatch.UNKNOWN).mapToLong(taken -> taken.lockAtMost)),
			landed.stream().map(taken -> taken.lockTakenBy).filter(Objects::nonNull).distinct().collect(Collectors.joining("; ")),
			String.valueOf(latest.plannedLegTicks));
	}

	/** Commit to landing, plus the ticks the game surely took no click after it. */
	private static long gameTicks(Taken taken)
	{
		return taken.commitToLand + taken.lockAtLeast;
	}

	private static long count(List<Taken> samples, Taken.Outcome outcome)
	{
		return samples.stream().filter(taken -> taken.outcome == outcome).count();
	}
}
