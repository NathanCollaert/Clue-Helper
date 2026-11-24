package com.cluehelper;

import com.cluehelper.CrossingTrace.Crossed;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One row per walk row, or per closed door that is none (priced 0: the walk takes it as open). Each sample's control
 * (standing at it to across, plus the lock) is a range, since the lock is only bounded by walk clicks; "longer" counts
 * the samples that took longer than priced even at their lowest, "shorter" those that took less even at their highest.
 */
final class CrossingTimings
{
	private static final String HEADER = String.join("\t", "label_or_click", "object", "origin", "kind", "source", "duration",
		"planned_half_ticks", "taken", "across", "cross_ticks", "lock_at_least", "lock_at_most", "fits", "longer", "shorter");

	private CrossingTimings()
	{
	}

	static String tsv(List<Crossed> all)
	{
		Map<String, List<Crossed>> byRow = all.stream().collect(Collectors.groupingBy(
			crossed -> (crossed.walkRow ? crossed.label : crossed.click) + "\t" + crossed.objectId + "\t" + crossed.origin,
			TreeMap::new, Collectors.toList()));
		StringBuilder out = new StringBuilder(HEADER).append('\n');
		byRow.forEach((key, samples) -> out.append(row(key, samples)).append('\n'));
		return out.toString();
	}

	private static String row(String labelObjectOrigin, List<Crossed> samples)
	{
		Crossed latest = samples.get(samples.size() - 1);
		List<Crossed> across = samples.stream().filter(crossed -> crossed.outcome == Crossed.Outcome.ACROSS).collect(Collectors.toList());
		int planned = latest.plannedHalfTicks;
		long longer = across.stream().filter(crossed -> 2 * (crossed.crossTicks + crossed.lockAtLeast) > planned).count();
		long shorter = across.stream()
			.filter(crossed -> crossed.lockAtMost != LockWatch.UNKNOWN && 2 * (crossed.crossTicks + crossed.lockAtMost) < planned)
			.count();
		return String.join("\t", labelObjectOrigin, String.valueOf(latest.kind), String.valueOf(latest.source),
			String.valueOf(latest.duration), String.valueOf(planned), String.valueOf(samples.size()), String.valueOf(across.size()),
			TimingLog.range(across.stream().mapToLong(crossed -> crossed.crossTicks)),
			TimingLog.range(across.stream().mapToLong(crossed -> crossed.lockAtLeast)),
			TimingLog.range(across.stream().filter(crossed -> crossed.lockAtMost != LockWatch.UNKNOWN).mapToLong(crossed -> crossed.lockAtMost)),
			String.valueOf(across.size() - longer - shorter), String.valueOf(longer), String.valueOf(shorter));
	}
}
