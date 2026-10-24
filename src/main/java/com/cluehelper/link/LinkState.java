package com.cluehelper.link;

import java.util.Objects;

/**
 * ClueLink's rules without the client, so recorded logs replay through them. A new step is told by its key, never by
 * object identity: core hands out the same static entry again for re-reads and later trails.
 */
final class LinkState
{
	private volatile boolean active;
	private volatile boolean needsRead;
	private volatile boolean hasStep;
	private String lastKey;
	private Object lastTargets;

	void start(boolean coreActive)
	{
		hasStep = false;
		needsRead = false;
		lastKey = null;
		lastTargets = null;
		setActive(coreActive);
	}

	/** Swing thread. @return whether the active flag changed */
	boolean onPluginChanged(boolean loaded)
	{
		return setActive(loaded);
	}

	StepChange tick(boolean coreActive, String key, Object targets, boolean inventoryHoldsClue)
	{
		setActive(coreActive);
		String seen = coreActive ? key : null;
		needsRead = coreActive && seen == null && inventoryHoldsClue;
		StepChange change = observe(seen, targets);
		lastKey = seen;
		lastTargets = seen == null ? null : targets;
		hasStep = seen != null;
		return change;
	}

	boolean isActive()
	{
		return active;
	}

	boolean needsRead()
	{
		return needsRead;
	}

	boolean hasStep()
	{
		return hasStep;
	}

	private StepChange observe(String key, Object targets)
	{
		if (key == null)
		{
			return hasStep ? StepChange.CLEARED : StepChange.UNCHANGED;
		}
		if (!hasStep || !key.equals(lastKey))
		{
			return StepChange.NEW;
		}
		return Objects.equals(targets, lastTargets) ? StepChange.UNCHANGED : StepChange.UPDATED;
	}

	private boolean setActive(boolean nowActive)
	{
		boolean changed = active != nowActive;
		active = nowActive;
		return changed;
	}
}
