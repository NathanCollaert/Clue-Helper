package com.cluehelper.guide;

import net.runelite.api.ObjectComposition;

final class OneClick
{
	private OneClick()
	{
	}

	static boolean teleportsTo(String option, String destination)
	{
		return option != null && destination != null && !MenuLabels.isOpener(option) && MenuLabels.matches(option, destination);
	}

	/** The house settings put the jewellery box's and nexus' left-click destination on their varbit-picked form. */
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
