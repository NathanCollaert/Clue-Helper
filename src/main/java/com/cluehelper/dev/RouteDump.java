package com.cluehelper.dev;

import com.cluehelper.route.Plan;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.Route;
import com.cluehelper.route.RoutePlanner;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** {@code ::ch-routes} and the {@code [CH-ROUTE]} log lines: every candidate with its ticks, rank and spent. */
final class RouteDump
{
	static final String TAG = "[CH-ROUTE]";
	private static final double NANOS_PER_MS = 1_000_000.0;

	private final RoutePlanner routes;

	RouteDump(RoutePlanner routes)
	{
		this.routes = routes;
	}

	/** @param chat the short lines; {@code logOnly} gets every candidate */
	void describe(Consumer<String> chat, Consumer<String> logOnly)
	{
		Plan plan = routes.getPlan();
		chat.accept(header(plan));
		summary(plan).forEach(chat);
		candidates(plan).forEach(logOnly);
	}

	String header(Plan plan)
	{
		PlanStart start = routes.getStart();
		return String.format("Plan %s: %d searches, %d labels, %.1f ms (judging %.1f ms); start %s, nearest node %d, scene BFS %.2f ms%s",
			plan.getStepKey(), plan.getSearches(), plan.getLabels(), plan.getSearchNanos() / NANOS_PER_MS,
			plan.getContextNanos() / NANOS_PER_MS, start == null ? "none" : start.getKind(), routes.getNearestNode(),
			routes.getLastBfsNanos() / NANOS_PER_MS, plan.getNote() == null ? "" : "; " + plan.getNote());
	}

	List<String> summary(Plan plan)
	{
		List<String> lines = new ArrayList<>();
		line(lines, "Best", plan.getBest());
		line(lines, "Committed", routes.getCommitted() == plan.getBest() ? null : routes.getCommitted());
		line(lines, "Direct", plan.getDirect());
		line(lines, "Same book", plan.getSameBook());
		line(lines, "Faster, under the margin", plan.getFasterUnqualified());
		plan.getAlternatives().forEach(route -> line(lines, "Alt", route));
		return lines;
	}

	List<String> candidates(Plan plan)
	{
		List<String> lines = new ArrayList<>();
		plan.getCandidates().forEach(candidate -> line(lines, "candidate (" + candidate.getCategory() + ")", candidate.getRoute()));
		plan.getProbeTicks().forEach((tile, ticks) ->
			lines.add("probe " + tile.getX() + "," + tile.getY() + "," + tile.getPlane() + " " + ticks + "t"));
		return lines;
	}

	private static void line(List<String> lines, String label, Route route)
	{
		if (route != null)
		{
			lines.add(label + " " + route.getTicks() + "t (rank " + (route.getRankHalfTicks() + 1) / 2 + ", " + route.getConfidence()
				+ "): " + route.describe() + " " + route.getSpent()
				+ (route.getEndBook() != route.getStartBook() ? " leaves you on " + route.getEndBook() : ""));
		}
	}
}
