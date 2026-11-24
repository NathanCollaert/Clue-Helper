package com.cluehelper;

import com.cluehelper.ActTrace.Acted;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * One row per act, by how the guide knows it is done and its first click's option. Each column is the ticks from
 * standing there to that effect, over the acts that had it: server effects by E + 1 - start, the guide and the clue by
 * their own tick - start.
 */
final class ActTimings
{
	private static final String HEADER = String.join("\t", "done", "option", "acts", "to_animation", "to_dialogue",
		"to_message", "to_guide_moved_on", "to_clue_changed", "clicks");

	private ActTimings()
	{
	}

	static String tsv(List<Acted> all)
	{
		Map<String, List<Acted>> byAct = all.stream().collect(Collectors.groupingBy(
			acted -> acted.done + "\t" + option(acted.click), TreeMap::new, Collectors.toList()));
		StringBuilder out = new StringBuilder(HEADER).append('\n');
		byAct.forEach((key, acts) -> out.append(row(key, acts)).append('\n'));
		return out.toString();
	}

	private static String row(String doneAndOption, List<Acted> acts)
	{
		return String.join("\t", doneAndOption, String.valueOf(acts.size()),
			serverEffect(acts, acted -> acted.animationTick),
			serverEffect(acts, acted -> acted.dialogueTick),
			serverEffect(acts, acted -> acted.messageTick),
			clientEffect(acts, acted -> acted.guideMovedOnTick),
			clientEffect(acts, acted -> acted.clueChangedTick),
			TimingLog.range(acts.stream().mapToLong(acted -> acted.clicks)));
	}

	private static String serverEffect(List<Acted> acts, ToIntFunction<Acted> tick)
	{
		return TimingLog.range(acts.stream()
			.filter(acted -> acted.startTick != 0 && tick.applyAsInt(acted) != 0)
			.mapToLong(acted -> tick.applyAsInt(acted) + 1 - acted.startTick));
	}

	private static String clientEffect(List<Acted> acts, ToIntFunction<Acted> tick)
	{
		return TimingLog.range(acts.stream()
			.filter(acted -> acted.startTick != 0 && tick.applyAsInt(acted) != 0)
			.mapToLong(acted -> tick.applyAsInt(acted) - acted.startTick));
	}

	/** "Dig" from "Dig ", "Talk-to" from "Talk-to Hans": the target varies per step. */
	private static String option(String click)
	{
		int space = click.indexOf(' ');
		return space < 0 ? click : click.substring(0, space);
	}
}
