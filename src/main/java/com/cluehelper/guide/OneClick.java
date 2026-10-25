package com.cluehelper.guide;

import java.util.Set;

final class OneClick
{
	/** Left clicks of teleport items that don't teleport. */
	private static final Set<String> NOT_TELEPORTS = Set.of("wield", "wear", "equip", "remove", "use", "drop", "examine", "check",
		"inspect", "read");

	private OneClick()
	{
	}

	static boolean teleportsTo(String option, String destination)
	{
		return option != null && destination != null && !MenuLabels.isOpener(option) && MenuLabels.matches(option, destination);
	}

	/**
	 * An item with one destination names no option in its row ("Skull sceptre" left-clicks "Invoke"), so any left click
	 * that is an action of its own is its teleport.
	 */
	static boolean isOwnTeleport(String option)
	{
		return option != null && !MenuLabels.isOpener(option) && !NOT_TELEPORTS.contains(MenuLabels.normalise(option));
	}
}
