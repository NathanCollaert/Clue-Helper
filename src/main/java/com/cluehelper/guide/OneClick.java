package com.cluehelper.guide;

import net.runelite.api.ObjectComposition;

/** Whether a left click teleports straight to the destination, without a menu or interface in between (B16). */
final class OneClick
{
	private OneClick()
	{
	}

	static boolean teleportsTo(String option, String destination)
	{
		return option != null && destination != null && !MenuLabels.isOpener(option) && MenuLabels.matches(option, destination);
	}

	/**
	 * An object's left click before any swap: its first action as the varbit-picked form shows it, which is how the
	 * house settings put a destination on the jewellery box or nexus.
	 */
	static String defaultClick(ObjectComposition object)
	{
		ObjectComposition shown = object.getImpostorIds() != null ? object.getImpostor() : object;
		String[] actions = shown == null ? null : shown.getActions();
		if (actions == null)
		{
			return null;
		}
		for (String action : actions)
		{
			if (action != null)
			{
				return action;
			}
		}
		return null;
	}
}
