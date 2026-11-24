package com.cluehelper;

import com.cluehelper.CrossingTrace.Crossed;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One row per walk row, or per closed door that is none (priced 0: the walk takes it as open). The game's share is
 * standing at it to across plus the lock's lower bound, in half-ticks as the walk table prices it; its verdict judges the
 * median only once {@link TimingLog.Batch#ENOUGH} crossings are in. The lock's upper bound is when the player clicked
 * again.
 */
final class CrossingTimings
{
	private static final String HEADER = String.join("\t", "label_or_click", "object", "origin", "kind", "source", "duration",
		"planned_half_ticks", "taken", "across", "game_cross_ticks", "game_lock_at_least", "game_median_half_ticks", "verdict",
		"player_lock_at_most", "lock_taken_by");

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
		return String.join("\t", labelObjectOrigin, String.valueOf(latest.kind), String.valueOf(latest.source),
			String.valueOf(latest.duration), String.valueOf(planned), String.valueOf(samples.size()), String.valueOf(across.size()),
			TimingLog.range(across.stream().mapToLong(crossed -> crossed.crossTicks)),
			TimingLog.range(across.stream().mapToLong(crossed -> crossed.lockAtLeast)),
			TimingLog.median(across.stream().mapToLong(CrossingTimings::gameHalfTicks)),
			TimingLog.verdict(across.stream().mapToLong(CrossingTimings::gameHalfTicks), planned),
			TimingLog.range(across.stream().filter(crossed -> crossed.lockAtMost != LockWatch.UNKNOWN).mapToLong(crossed -> crossed.lockAtMost)),
			across.stream().map(crossed -> crossed.lockTakenBy).filter(Objects::nonNull).distinct().collect(Collectors.joining("; ")));
	}

	/** Standing at it to across, plus the ticks the game surely took no click after: what the walk table prices, in its unit. */
	private static long gameHalfTicks(Crossed crossed)
	{
		return 2L * (crossed.crossTicks + crossed.lockAtLeast);
	}
}
