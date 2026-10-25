package com.cluehelper.route;

import com.cluehelper.player.Container;
import java.util.stream.Collectors;

/** A route as one line of a golden file: its ticks, its legs, what it spends and withdraws, and the book it ends on. */
public final class RouteText
{
	private RouteText()
	{
	}

	public static String describe(Route route)
	{
		if (route == null)
		{
			return "none";
		}
		String extras = route.getRankHalfTicks() != route.getHalfTicks() ? " (rank " + (route.getRankHalfTicks() + 1) / 2 + ")" : "";
		String spent = route.getSpent().isEmpty() ? "" : " " + route.getSpent();
		String ends = route.getEndBook() != route.getStartBook() ? " leaves you on " + route.getEndBook() : "";
		return route.getTicks() + "t" + extras + ": " + (route.isOpenBankStart() ? "(at the open bank) " : "") + route.describe() + spent
			+ ends + withdraw(route);
	}

	private static String withdraw(Route route)
	{
		if (!route.isBanked())
		{
			return "";
		}
		String items = route.getWithdraw().stream().map(use -> use.getItemId() + (use.getContainer() == Container.GROUP_STORAGE ? " (group)" : ""))
			.collect(Collectors.joining(", "));
		return " withdraw [" + items + "]" + (route.isDepositFirst() ? " deposit first" : "") + (route.isPinIncluded() ? " +PIN" : "");
	}
}
