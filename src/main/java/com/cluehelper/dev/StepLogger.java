package com.cluehelper.dev;

import com.cluehelper.memory.MemorySlot;
import com.cluehelper.memory.MemoryStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Collects step frequencies to weight loadouts later. Records only while enabled, which is developer mode. */
@Singleton
public class StepLogger
{
	static final String KEY = "devStepLog";
	/** Over 150 steps per tier many times over, while keeping the profile's config small. */
	static final int MAX_ENTRIES = 5000;

	private final MemorySlot<StepLog> slot;
	private volatile boolean enabled;

	@Inject
	StepLogger(MemoryStore memory)
	{
		this.slot = memory.slot(KEY, StepLog.class, StepLog::new);
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
		if (log.endsWith(key))
		{
			return;
		}
		log.append(new StepLog.Entry(tier, type, key, Instant.now().getEpochSecond()), MAX_ENTRIES);
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
