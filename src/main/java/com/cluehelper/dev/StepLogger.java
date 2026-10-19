package com.cluehelper.dev;

import com.cluehelper.memory.MemorySlot;
import com.cluehelper.memory.MemoryStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * S12: records tier, type and key of every resolved step in the current profile, so step
 * frequencies accrue during v1 for v2's loadout weights. Records only while enabled, which is
 * developer mode.
 */
@Singleton
public class StepLogger
{
	static final String KEY = "devStepLog";
	/** Over 150 steps per tier many times over, while keeping the profile's config small. */
	static final int MAX_ENTRIES = 5000;

	private final MemorySlot<StepLog> slot;
	private final LongSupplier epochSeconds;
	private final int maxEntries;
	private volatile boolean enabled;

	@Inject
	StepLogger(MemoryStore memory)
	{
		this(memory, () -> Instant.now().getEpochSecond(), MAX_ENTRIES);
	}

	StepLogger(MemoryStore memory, LongSupplier epochSeconds, int maxEntries)
	{
		this.slot = memory.slot(KEY, StepLog.class, StepLog::new);
		this.epochSeconds = epochSeconds;
		this.maxEntries = maxEntries;
	}

	public void setEnabled(boolean enabled)
	{
		this.enabled = enabled;
	}

	public synchronized void record(String tier, String type, String key)
	{
		if (!enabled)
		{
			return;
		}
		StepLog log = slot.get();
		log.append(new StepLog.Entry(tier, type, key, epochSeconds.getAsLong()), maxEntries);
		slot.set(log);
	}

	public synchronized List<StepLog.Entry> entries()
	{
		return new ArrayList<>(slot.get().entries());
	}

	public synchronized void clear()
	{
		slot.clear();
	}
}
