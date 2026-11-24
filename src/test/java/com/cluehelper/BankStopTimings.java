package com.cluehelper;

import com.cluehelper.BankStopTrace.Stopped;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One row per item-stack count, with and without a PIN: the base stop is the 0-stack row, and the step between rows
 * is the cost of one stack.
 */
final class BankStopTimings
{
	private static final String HEADER = String.join("\t", "item_stacks", "pin", "stops", "stop_ticks", "stop_ticks_median",
		"stand_to_open", "pin_ticks", "clicks");

	private BankStopTimings()
	{
	}

	static String tsv(List<Stopped> all)
	{
		Map<String, List<Stopped>> byStacksAndPin = all.stream().collect(Collectors.groupingBy(
			stopped -> String.format("%03d\t%s", stopped.itemStacks, stopped.pinOpenTick != 0), TreeMap::new, Collectors.toList()));
		StringBuilder out = new StringBuilder(HEADER).append('\n');
		byStacksAndPin.forEach((key, stops) -> out.append(row(key, stops)).append('\n'));
		return out.toString();
	}

	private static String row(String stacksAndPin, List<Stopped> stops)
	{
		List<Integer> stopTicks = stops.stream().map(stopped -> stopped.stopTicks).sorted().collect(Collectors.toList());
		return String.join("\t", stacksAndPin.replaceFirst("^0+(?=\\d)", ""), String.valueOf(stops.size()),
			TimingLog.range(stops.stream().mapToLong(stopped -> stopped.stopTicks)),
			String.valueOf(stopTicks.get(stopTicks.size() / 2)),
			TimingLog.range(stops.stream().mapToLong(stopped -> stopped.standToOpen)),
			TimingLog.range(stops.stream().filter(stopped -> stopped.pinOpenTick != 0).mapToLong(stopped -> stopped.pinTicks)),
			TimingLog.range(stops.stream().mapToLong(stopped -> stopped.clicks)));
	}
}
