package com.cluehelper;

import com.cluehelper.StashTrace.Searched;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** One row per withdraw or deposit, with and without the PIN. */
final class StashTimings
{
	private static final String HEADER = String.join("\t", "withdrew", "pin", "searches", "click_to_line", "stand_to_line");

	private StashTimings()
	{
	}

	static String tsv(List<Searched> all)
	{
		Map<String, List<Searched>> byKind = all.stream().collect(Collectors.groupingBy(
			searched -> searched.withdrew + "\t" + searched.pin, TreeMap::new, Collectors.toList()));
		StringBuilder out = new StringBuilder(HEADER).append('\n');
		byKind.forEach((key, searches) -> out.append(String.join("\t", key, String.valueOf(searches.size()),
			TimingLog.range(searches.stream().mapToLong(searched -> searched.clickToLine)),
			TimingLog.range(searches.stream().mapToLong(searched -> searched.standToLine)))).append('\n'));
		return out.toString();
	}
}
