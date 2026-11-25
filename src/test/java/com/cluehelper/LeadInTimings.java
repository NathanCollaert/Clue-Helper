package com.cluehelper;

import com.cluehelper.LegTimingTrace.Taken;
import com.cluehelper.guide.SubStep;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * One row per transport category (or spellbook change): the player's lead-in before a leg's first click, against the
 * one tick the planner adds per teleport or fairy ring to rank routes. Only the lead-in between legs (after a walk or
 * another transport) is what that tick stands for, so only it gets a verdict; after an act the player also reads a new
 * clue, and after a bank stop the bank stop's own margin prices it, so those are shown apart.
 */
final class LeadInTimings
{
	private static final String HEADER = String.join("\t", "category", "between_legs", "between_ticks", "between_median",
		"between_verdict", "planned_tie_ticks", "after_act", "after_act_ticks", "after_act_median", "after_bank",
		"after_bank_ticks", "after_bank_median");
	private static final Set<SubStep.Kind> ACT = EnumSet.of(SubStep.Kind.ACT);
	private static final Set<SubStep.Kind> BANK = EnumSet.of(SubStep.Kind.BANK, SubStep.Kind.WITHDRAW);
	private static final int TIE_TICKS = WaitWindows.constant("com.cluehelper.route.PlanContext", "TELEPORT_TIE_HALF_TICKS") / 2;

	private LeadInTimings()
	{
	}

	static String tsv(List<Taken> all)
	{
		Map<String, List<Taken>> byCategory = all.stream()
			.filter(taken -> taken.leadInTicks != LockWatch.UNKNOWN)
			.collect(Collectors.groupingBy(LeadInTimings::category, TreeMap::new, Collectors.toList()));
		StringBuilder out = new StringBuilder(HEADER).append('\n');
		byCategory.forEach((category, samples) -> out.append(row(category, samples)).append('\n'));
		return out.toString();
	}

	private static String category(Taken taken)
	{
		return taken.category != null ? taken.category : String.valueOf(taken.kind);
	}

	private static String row(String category, List<Taken> samples)
	{
		Predicate<Taken> afterAct = taken -> taken.leadInAfter == null || ACT.contains(taken.leadInAfter);
		Predicate<Taken> afterBank = taken -> taken.leadInAfter != null && BANK.contains(taken.leadInAfter);
		long[] between = leadIns(samples, afterAct.negate().and(afterBank.negate()));
		long[] act = leadIns(samples, afterAct);
		long[] bank = leadIns(samples, afterBank);
		return String.join("\t", category, String.valueOf(between.length), TimingLog.range(LongStream.of(between)),
			TimingLog.median(LongStream.of(between)), TimingLog.verdict(LongStream.of(between), TIE_TICKS),
			String.valueOf(TIE_TICKS), String.valueOf(act.length), TimingLog.range(LongStream.of(act)),
			TimingLog.median(LongStream.of(act)), String.valueOf(bank.length), TimingLog.range(LongStream.of(bank)),
			TimingLog.median(LongStream.of(bank)));
	}

	private static long[] leadIns(List<Taken> samples, Predicate<Taken> which)
	{
		return samples.stream().filter(which).mapToLong(taken -> taken.leadInTicks).toArray();
	}
}
