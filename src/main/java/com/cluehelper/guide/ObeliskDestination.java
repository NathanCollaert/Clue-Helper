package com.cluehelper.guide;

/**
 * Client thread only. An obelisk takes two clicks: Set Destination and its list line, then Teleport to Destination.
 * The list was open on the target's line: its closing means the destination is set.
 */
final class ObeliskDestination
{
	private boolean listSeen;
	private String setFor;

	/**
	 * Call every tick. {@code watching} while the step's menu target is the obelisk's list line; {@code showing} while
	 * that list is open on it.
	 *
	 * @return whether the destination was just set for {@code rowName}
	 */
	boolean onGameTick(boolean watching, boolean showing, String rowName)
	{
		if (!watching)
		{
			listSeen = false;
			return false;
		}
		if (showing)
		{
			listSeen = true;
			return false;
		}
		if (!listSeen)
		{
			return false;
		}
		listSeen = false;
		setFor = rowName;
		return true;
	}

	boolean isSetFor(String rowName)
	{
		return rowName.equals(setFor);
	}

	void clear()
	{
		listSeen = false;
		setFor = null;
	}
}
