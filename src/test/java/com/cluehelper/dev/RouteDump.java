package com.cluehelper.dev;

import com.cluehelper.route.Plan;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.Route;
import com.cluehelper.route.RoutePlanner;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class RouteDump
{
	static final String TAG = "[CH-ROUTE]";
	private static final double NANOS_PER_MS = 1_000_000.0;

	private final RoutePlanner routes;

	RouteDump(RoutePlanner routes)
	{
		this.routes = routes;
	}

	String header(Plan plan)
	{
		PlanStart start = routes.getStart();
		return String.format("Plan %s: %d labels, %.1f ms; start %s%s%s",
			plan.getStepKey(), plan.getLabels(), plan.getSearchNanos() / NANOS_PER_MS, start == null ? "none" : start.getKind(),
			plan.getNote() == null ? "" : "; " + plan.getNote(),
			plan.getRequirementNotes().isEmpty() ? "" : "; needs: " + String.join("; ", plan.getRequirementNotes()));
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

	private static String withdraw(Route route)
	{
		if (!route.isBanked())
		{
			return "";
		}
		return " withdraw " + route.getWithdraw().stream().map(use -> String.valueOf(use.getItemId())).collect(Collectors.joining(",", "[", "]"))
			+ (route.isDepositFirst() ? " depositFirst" : "") + (route.isPinIncluded() ? " +PIN" : "");
	}

	private static void line(List<String> lines, String label, Route route)
	{
		if (route != null)
		{
			lines.add(label + " " + route.getTicks() + "t (rank " + (route.getRankHalfTicks() + 1) / 2 + ", " + route.getConfidence()
				+ "): " + (route.isOpenBankStart() ? "(open bank) " : "") + route.describe() + " " + route.getSpent()
				+ (route.getEndBook() != route.getStartBook() ? " leaves you on " + route.getEndBook() : "") + withdraw(route));
		}
	}
}
