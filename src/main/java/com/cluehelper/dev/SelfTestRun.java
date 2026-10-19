package com.cluehelper.dev;

/** What a running {@link SelfTest} reports to. Safe to use from any thread. */
public interface SelfTestRun
{
	void check(boolean ok, String what);

	/** The test can't run in this state (e.g. logged out); counted apart from passes and failures. */
	void skip(String why);

	void done();
}
