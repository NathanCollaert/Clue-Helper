package com.cluehelper;

import com.cluehelper.ActTrace.Acted;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * One row per act, by how the guide knows it is done and its first click's option. The game columns are the ticks from
 * standing there to that sign, E + 1 - start, over the acts that had it; the guide and clue columns leave out acts on
 * mocked clues, which move on only when the next clue is mocked. The player column is the reaction: the guide showing
 * the act to the first click. Nothing here is priced by the planner, so a column needs
 * {@link TimingLog.Batch#ENOUGH} acts before it means much; "batch" says how far off that is.
 */
final class ActTimings
{
	private static final String HEADER = String.join("\t", "done", "option", "acts", "batch", "game_to_animation",
		"game_to_dialogue", "game_to_message", "game_to_items", "guide_moved_on", "clue_changed", "player_reaction_median",
		"player_reaction", "clicks", "mocked");

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
		List<Acted> real = acts.stream().filter(acted -> !acted.mocked).collect(Collectors.toList());
		List<Acted> reacted = acts.stream().filter(acted -> acted.shownTick != 0).collect(Collectors.toList());
		return String.join("\t", doneAndOption, String.valueOf(acts.size()),
			acts.size() < TimingLog.Batch.ENOUGH ? TimingLog.Batch.needs(acts.size()) : "enough",
			ticksTo(acts, acted -> acted.animationTick),
			ticksTo(acts, acted -> acted.dialogueTick),
			ticksTo(acts, acted -> acted.messageTick),
			ticksTo(acts, acted -> acted.itemsChangedTick),
			ticksTo(real, acted -> acted.guideMovedOnTick),
			ticksTo(real, acted -> acted.clueChangedTick),
			TimingLog.median(reacted.stream().mapToLong(acted -> acted.clickTick - acted.shownTick)),
			TimingLog.range(reacted.stream().mapToLong(acted -> acted.clickTick - acted.shownTick)),
			TimingLog.range(acts.stream().mapToLong(acted -> acted.clicks)),
			String.valueOf(acts.size() - real.size()));
	}

	private static String ticksTo(List<Acted> acts, ToIntFunction<Acted> tick)
	{
		return TimingLog.range(acts.stream()
			.filter(acted -> acted.startTick != 0 && tick.applyAsInt(acted) != 0)
			.mapToLong(acted -> tick.applyAsInt(acted) + 1 - acted.startTick));
	}

	/** "Dig" from "Dig ", "Talk-to" from "Talk-to Hans": the target varies per step. */
	private static String option(String click)
	{
		int space = click.indexOf(' ');
		return space < 0 ? click : click.substring(0, space);
	}
}
