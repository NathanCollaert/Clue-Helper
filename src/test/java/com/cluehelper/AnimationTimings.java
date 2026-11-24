package com.cluehelper;

import com.cluehelper.AnimationTrace.Played;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * One row per animation and the click that led to it. Played times count only runs that ended on their own with no
 * click in between, so a cut-short run never shortens the length.
 */
final class AnimationTimings
{
	private static final String NO_CLICK = "-";
	private static final String HEADER = String.join("\t", "animation", "click", "seen", "batch", "full_runs", "cache_ms", "loops",
		"played_ms_min", "played_ms_median", "played_ms_max", "click_to_start_ticks", "graphics");

	private AnimationTimings()
	{
	}

	static String tsv(List<Played> all)
	{
		Map<String, List<Played>> byAnimationAndClick = all.stream().collect(Collectors.groupingBy(
			played -> played.animation + "\t" + (played.click == null ? NO_CLICK : played.click), TreeMap::new, Collectors.toList()));
		StringBuilder out = new StringBuilder(HEADER).append('\n');
		byAnimationAndClick.forEach((key, runs) -> out.append(row(key, runs)).append('\n'));
		return out.toString();
	}

	private static String row(String animationAndClick, List<Played> runs)
	{
		List<Long> fullRuns = runs.stream()
			.filter(AnimationTimings::ranToItsEnd)
			.map(played -> played.playedMs)
			.sorted()
			.collect(Collectors.toList());
		Played any = runs.get(0);
		TreeSet<Integer> graphics = new TreeSet<>();
		runs.forEach(played -> graphics.addAll(played.graphics));
		return String.join("\t", animationAndClick, String.valueOf(runs.size()),
			fullRuns.size() < TimingLog.Batch.ENOUGH ? TimingLog.Batch.needs(fullRuns.size()) + " full runs" : "enough",
			String.valueOf(fullRuns.size()),
			String.valueOf(any.cacheMs), String.valueOf(any.loops),
			fullRuns.isEmpty() ? "" : String.valueOf(fullRuns.get(0)),
			fullRuns.isEmpty() ? "" : String.valueOf(fullRuns.get(fullRuns.size() / 2)),
			fullRuns.isEmpty() ? "" : String.valueOf(fullRuns.get(fullRuns.size() - 1)),
			TimingLog.range(runs.stream().filter(played -> played.click != null).mapToLong(played -> played.clickToStartTicks)),
			graphics.stream().map(String::valueOf).collect(Collectors.joining(",")));
	}

	private static boolean ranToItsEnd(Played played)
	{
		return played.end == Played.End.STOPPED && !played.clickedDuring;
	}
}
