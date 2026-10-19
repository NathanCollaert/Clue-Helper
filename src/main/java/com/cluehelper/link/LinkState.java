package com.cluehelper.link;

import java.util.Objects;

/**
 * ClueLink's rules without the client (B03), so recorded logs replay through them:
 * <ul>
 * <li>core active: seeded at start, set by {@code PluginChanged}, re-checked every tick;</li>
 * <li>a new step is a key change or any key after a read without one; never object identity,
 * because core hands out the same static entry again for re-reads and later trails;</li>
 * <li>a clue needs reading when core is active, has no clue, and the inventory holds one.</li>
 * </ul>
 */
final class LinkState
{
	private volatile boolean active;
	private volatile boolean needsRead;
	private volatile boolean hasStep;
	private String lastKey;
	private Object lastTargets;

	/** @return whether the active flag changed */
	boolean start(boolean coreActive)
	{
		hasStep = false;
		needsRead = false;
		lastKey = null;
		lastTargets = null;
		return setActive(coreActive);
	}

	/** Runs on the Swing thread. @return whether the active flag changed */
	boolean onPluginChanged(boolean loaded)
	{
		return setActive(loaded);
	}

	/**
	 * One read of core at GameTick priority -1.
	 *
	 * @param key the step key of core's clue, or null when core has none
	 * @param targets compared with the last read's to tell an updated step
	 */
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
