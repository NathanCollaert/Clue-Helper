package com.cluehelper;

import com.cluehelper.BankStopTrace.Stopped;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * One row per item-stack count, with and without a PIN, each with its game and player share; then the cost the planner
 * gives a stop (PlanContext's bank stop, per-stack withdraw and PIN half-ticks, read from the plugin) against a fit of
 * the stops without a PIN: a base and a cost per stack, for the whole stop and for each share. A fit is judged only on
 * {@link TimingLog.Batch#ENOUGH} stops; stops recorded before the split count in the totals only.
 */
final class BankStopTimings
{
	private static final String PLAN = "com.cluehelper.route.PlanContext";
	private static final double BASE_TICKS = WaitWindows.constant(PLAN, "BANK_STOP_HALF_TICKS") / 2.0;
	private static final double STACK_TICKS = WaitWindows.constant(PLAN, "WITHDRAW_HALF_TICKS") / 2.0;
	private static final double PIN_TICKS = WaitWindows.constant(PLAN, "PIN_HALF_TICKS") / 2.0;
	private static final String HEADER = String.join("\t", "item_stacks", "pin", "stops", "stop_ticks", "stop_median",
		"planned", "game_median", "player_median", "stand_to_open", "pin_ticks", "clicks");

	private BankStopTimings()
	{
	}

	static String tsv(List<Stopped> all)
	{
		Map<String, List<Stopped>> byStacksAndPin = all.stream().collect(Collectors.groupingBy(
			stopped -> String.format("%03d\t%s", stopped.itemStacks, stopped.pinOpenTick != 0), TreeMap::new, Collectors.toList()));
		StringBuilder out = new StringBuilder(HEADER).append('\n');
		byStacksAndPin.forEach((key, stops) -> out.append(row(key, stops)).append('\n'));
		List<Stopped> withoutPin = all.stream().filter(stopped -> stopped.pinOpenTick == 0).collect(Collectors.toList());
		List<Stopped> split = withoutPin.stream().filter(BankStopTimings::isSplit).collect(Collectors.toList());
		out.append('\n').append("fit of the stops without a PIN\tstops\tbase\tplanned_base\tper_stack\tplanned_per_stack\tverdict\n");
		out.append(fit("whole stop", withoutPin, stopped -> stopped.stopTicks, true));
		out.append(fit("game share", split, stopped -> stopped.gameTicks, false));
		out.append(fit("player share", split, stopped -> stopped.playerTicks, false));
		out.append("planned PIN\t").append(PIN_TICKS).append('\n');
		return out.toString();
	}

	private static String row(String stacksAndPin, List<Stopped> stops)
	{
		Stopped any = stops.get(0);
		List<Stopped> split = stops.stream().filter(BankStopTimings::isSplit).collect(Collectors.toList());
		double planned = BASE_TICKS + STACK_TICKS * any.itemStacks + (any.pinOpenTick != 0 ? PIN_TICKS : 0);
		return String.join("\t", stacksAndPin.replaceFirst("^0+(?=\\d)", ""), String.valueOf(stops.size()),
			TimingLog.range(stops.stream().mapToLong(stopped -> stopped.stopTicks)),
			TimingLog.median(stops.stream().mapToLong(stopped -> stopped.stopTicks)),
			String.valueOf(planned),
			TimingLog.median(split.stream().mapToLong(stopped -> stopped.gameTicks)),
			TimingLog.median(split.stream().mapToLong(stopped -> stopped.playerTicks)),
			TimingLog.range(stops.stream().mapToLong(stopped -> stopped.standToOpen)),
			TimingLog.range(stops.stream().filter(stopped -> stopped.pinOpenTick != 0).mapToLong(stopped -> stopped.pinTicks)),
			TimingLog.range(stops.stream().mapToLong(stopped -> stopped.clicks)));
	}

	/** Least squares of ticks on item stacks; only the whole stop is judged, as the planner prices it whole. */
	private static String fit(String what, List<Stopped> stops, ToIntFunction<Stopped> ticks, boolean judged)
	{
		int n = stops.size();
		if (n < 2)
		{
			return what + "\t" + n + "\n";
		}
		double meanX = stops.stream().mapToInt(stopped -> stopped.itemStacks).average().orElse(0);
		double meanY = stops.stream().mapToInt(ticks).average().orElse(0);
		double covariance = stops.stream().mapToDouble(stopped -> (stopped.itemStacks - meanX) * (ticks.applyAsInt(stopped) - meanY)).sum();
		double variance = stops.stream().mapToDouble(stopped -> (stopped.itemStacks - meanX) * (stopped.itemStacks - meanX)).sum();
		double perStack = variance == 0 ? 0 : covariance / variance;
		double base = meanY - perStack * meanX;
		String verdict = !judged ? "" : n < TimingLog.Batch.ENOUGH ? TimingLog.Batch.needs(n)
			: String.format("base %+.1f, per stack %+.1f against the plan", base - BASE_TICKS, perStack - STACK_TICKS);
		return String.format("%s\t%d\t%.1f\t%s\t%.1f\t%s\t%s%n", what, n, base, judged ? String.valueOf(BASE_TICKS) : "", perStack,
			judged ? String.valueOf(STACK_TICKS) : "", verdict);
	}

	/** Recorded with the game and player split; earlier stops have neither. */
	private static boolean isSplit(Stopped stopped)
	{
		return stopped.gameTicks + stopped.playerTicks == stopped.stopTicks && stopped.gameTicks > 0;
	}
}
