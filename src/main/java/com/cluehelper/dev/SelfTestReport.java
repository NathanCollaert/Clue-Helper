package com.cluehelper.dev;

import java.util.ArrayList;
import java.util.List;

/** The totals of one self-test run. */
public final class SelfTestReport
{
	private int passed;
	private int failed;
	private int skipped;
	private final List<String> failures = new ArrayList<>();

	synchronized void record(String test, boolean ok, String what)
	{
		if (ok)
		{
			passed++;
		}
		else
		{
			failed++;
			failures.add(test + ": " + what);
		}
	}

	synchronized void skip()
	{
		skipped++;
	}

	public synchronized int passed()
	{
		return passed;
	}

	public synchronized int failed()
	{
		return failed;
	}

	public synchronized int skipped()
	{
		return skipped;
	}

	public synchronized List<String> failures()
	{
		return new ArrayList<>(failures);
	}

	@Override
	public synchronized String toString()
	{
		return passed + " passed, " + failed + " failed, " + skipped + " skipped";
	}
}
