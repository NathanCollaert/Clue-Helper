package com.cluehelper.campaign;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Value;

/**
 * The golden test's report on the steps whose outcome changed: {@code changed.txt}, which the campaign reads to put
 * route changes back in the queue and {@code acceptTickChanges} reads to take tick-only ones, and {@code changed.html},
 * the same steps side by side, grouped by what changed, the largest route group first.
 */
final class ChangeReport
{
	private static final String TICKS_ONLY = "Ticks only";
	private static final String GUIDE_ONLY = "Guide lines only";
	private static final String FIRST_LEG_END = " → ";

	@Value
	static class Changed
	{
		Path file;
		CampaignRecording recording;
		Outcome now;
		Outcome.Change change;
	}

	private ChangeReport()
	{
	}

	static void write(List<Changed> changed) throws IOException
	{
		Files.createDirectories(CampaignFiles.CHANGED.getParent());
		List<String> lines = changed.stream()
			.map(step -> (step.getChange() == Outcome.Change.TICKS ? "ticks" : "route") + "\t" + step.getRecording().key + "\t" + step.getFile())
			.collect(Collectors.toList());
		Files.write(CampaignFiles.CHANGED, lines, StandardCharsets.UTF_8);
		Files.write(CampaignFiles.CHANGED_PAGE, page(changed).getBytes(StandardCharsets.UTF_8));
	}

	private static String page(List<Changed> changed)
	{
		Map<String, List<Changed>> groups = new LinkedHashMap<>();
		changed.forEach(step -> groups.computeIfAbsent(group(step), g -> new ArrayList<>()).add(step));
		List<Map.Entry<String, List<Changed>>> ordered = new ArrayList<>(groups.entrySet());
		ordered.sort(Comparator.comparingInt((Map.Entry<String, List<Changed>> group) -> rank(group.getKey()))
			.thenComparing(group -> -group.getValue().size()));
		StringBuilder html = new StringBuilder("<!doctype html><meta charset='utf-8'><title>Campaign changes</title><style>"
			+ "body{font:13px sans-serif;margin:16px}table{border-collapse:collapse;width:100%;margin-bottom:24px}"
			+ "td{border:1px solid #ccc;padding:4px;vertical-align:top;font-family:monospace;white-space:pre-wrap}"
			+ ".diff{background:#ffd7d7}.key{font-weight:bold}</style>");
		html.append("<h1>").append(changed.size()).append(" changed steps</h1>");
		if (changed.isEmpty())
		{
			html.append("<p>Every finished step replays its signed-off outcome.</p>");
		}
		for (Map.Entry<String, List<Changed>> group : ordered)
		{
			html.append("<h2>").append(escape(group.getKey())).append(" (").append(group.getValue().size()).append(")</h2>");
			if (group.getKey().equals(TICKS_ONLY))
			{
				html.append("<p>Same route and guide. After reading them: <code>gradlew acceptTickChanges</code></p>");
			}
			html.append("<table><tr><th>Step</th><th>Signed off</th><th>Now</th></tr>");
			group.getValue().forEach(step -> html.append(row(step)));
			html.append("</table>");
		}
		return html.toString();
	}

	private static String group(Changed step)
	{
		if (step.getChange() == Outcome.Change.TICKS)
		{
			return TICKS_ONLY;
		}
		Outcome before = step.getRecording().expected;
		Outcome now = step.getNow();
		if (before.getRoute().equals(now.getRoute()))
		{
			return GUIDE_ONLY;
		}
		return "Route: " + firstLeg(before) + FIRST_LEG_END + firstLeg(now);
	}

	/** Route changes first, then guide-only changes, then tick-only ones. */
	private static int rank(String group)
	{
		return group.equals(TICKS_ONLY) ? 2 : group.equals(GUIDE_ONLY) ? 1 : 0;
	}

	private static String firstLeg(Outcome outcome)
	{
		String best = outcome.getRoute().isEmpty() ? "" : outcome.getRoute().get(0);
		int legs = best.indexOf(": ");
		String route = legs < 0 ? best : best.substring(legs + 2);
		int end = route.indexOf(FIRST_LEG_END);
		return "[" + (end < 0 ? route : route.substring(0, end)) + "]";
	}

	private static String row(Changed step)
	{
		Outcome before = step.getRecording().expected;
		Outcome now = step.getNow();
		return "<tr><td class='key'>" + escape(step.getRecording().tier + "\n" + step.getRecording().key) + "</td><td>"
			+ lines(before, now) + "</td><td>" + lines(now, before) + "</td></tr>";
	}

	private static String lines(Outcome shown, Outcome other)
	{
		StringBuilder cell = new StringBuilder();
		appendLines(cell, shown.getRoute(), other.getRoute());
		cell.append("\n");
		appendLines(cell, shown.getGuide(), other.getGuide());
		return cell.toString();
	}

	private static void appendLines(StringBuilder cell, List<String> shown, List<String> other)
	{
		for (int i = 0; i < shown.size(); i++)
		{
			boolean same = i < other.size() && other.get(i).equals(shown.get(i));
			cell.append(same ? "" : "<span class='diff'>").append(escape(shown.get(i))).append(same ? "" : "</span>").append("\n");
		}
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
