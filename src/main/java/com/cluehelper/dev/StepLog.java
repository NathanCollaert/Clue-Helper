package com.cluehelper.dev;

import java.util.ArrayList;
import java.util.List;

/** Oldest entry first. Stored as JSON in the profile, so field names are persisted keys. */
public final class StepLog
{
	private List<Entry> entries = new ArrayList<>();

	public List<Entry> entries()
	{
		if (entries == null)
		{
			entries = new ArrayList<>();
		}
		return entries;
	}

	/**
	 * ClueLink reports a re-read, a pick-up or a core re-enable as a new step, but for step
	 * frequencies it is the same step again.
	 */
	boolean endsWith(String key)
	{
		List<Entry> list = entries();
		return !list.isEmpty() && list.get(list.size() - 1).getKey().equals(key);
	}

	void append(Entry entry, int maxEntries)
	{
		List<Entry> list = entries();
		list.add(entry);
		if (list.size() > maxEntries)
		{
			list.subList(0, list.size() - maxEntries).clear();
		}
	}

	public static final class Entry
	{
		private final String tier;
		private final String type;
		private final String key;
		private final long epochSecond;

		Entry(String tier, String type, String key, long epochSecond)
		{
			this.tier = tier;
			this.type = type;
			this.key = key;
			this.epochSecond = epochSecond;
		}

		public String getTier()
		{
			return tier;
		}

		public String getType()
		{
			return type;
		}

		public String getKey()
		{
			return key;
		}

		public long getEpochSecond()
		{
			return epochSecond;
		}

		@Override
		public String toString()
		{
			return tier + " " + type + " " + key;
		}
	}
}
