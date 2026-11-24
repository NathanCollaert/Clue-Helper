package com.cluehelper;

import com.cluehelper.LegTimingTrace.Taken;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One row per catalog row or spellbook change: what the planner priced against what was measured. Each landed sample's
 * control (commit to landing plus the lock) is a range, since the lock is only bounded by walk clicks; "longer" counts
 * the samples that took longer than the planned Duration even at their lowest, "shorter" those that took less even at
 * their highest.
 */
final class LegTimings
{
	private static final String HEADER = String.join("\t", "kind", "name", "destination", "category", "planned_duration",
		"planned_menu_ticks", "planned_leg_ticks", "taken", "landed", "commit_to_land", "lock_at_least", "lock_at_most",
		"menu_ticks", "fits", "longer", "shorter");

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
		int planned = latest.plannedDuration;
		long longer = landed.stream().filter(taken -> controlAtLeast(taken) > planned).count();
		long shorter = landed.stream().filter(taken -> taken.lockAtMost != LockWatch.UNKNOWN && controlAtMost(taken) < planned).count();
		return String.join("\t", kindNameDestination, String.valueOf(latest.category), String.valueOf(planned),
			String.valueOf(latest.plannedMenuTicks), String.valueOf(latest.plannedLegTicks),
			String.valueOf(samples.size()), String.valueOf(landed.size()),
			TimingLog.range(landed.stream().mapToLong(taken -> taken.commitToLand)),
			TimingLog.range(landed.stream().mapToLong(taken -> taken.lockAtLeast)),
			TimingLog.range(landed.stream().filter(taken -> taken.lockAtMost != LockWatch.UNKNOWN).mapToLong(taken -> taken.lockAtMost)),
			TimingLog.range(landed.stream().filter(taken -> !taken.walkedBeforeCommit).mapToLong(taken -> taken.menuTicks)),
			String.valueOf(landed.size() - longer - shorter), String.valueOf(longer), String.valueOf(shorter));
	}

	private static int controlAtLeast(Taken taken)
	{
		return taken.commitToLand + taken.lockAtLeast;
	}

	private static int controlAtMost(Taken taken)
	{
		return taken.commitToLand + taken.lockAtMost;
	}
}
