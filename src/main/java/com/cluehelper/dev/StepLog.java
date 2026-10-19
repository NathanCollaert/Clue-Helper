package com.cluehelper.dev;

import java.util.ArrayList;
import java.util.List;

/** The S12 step log of one profile: every resolved step, oldest first. Stored as JSON. */
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

	/** Appends, dropping the oldest entries beyond {@code max}. */
	void append(Entry entry, int max)
	{
		List<Entry> list = entries();
		list.add(entry);
		if (list.size() > max)
		{
			list.subList(0, list.size() - max).clear();
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
