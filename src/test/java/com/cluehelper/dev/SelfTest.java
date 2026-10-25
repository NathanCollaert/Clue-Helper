package com.cluehelper.dev;

import java.util.function.Consumer;

public interface SelfTest
{
	String name();

	/**
	 * Starts on the client thread. Must end with {@link SelfTestRun#done()}, from any thread and
	 * possibly later; a test that never calls it fails on the runner's timeout.
	 */
	void run(SelfTestRun run);

	/** Tests run in ascending order, ties in registration order. Tests that restart plugins go last. */
	default int order()
	{
		return 0;
	}

	/** A test whose checks all happen on the client thread, at once. */
	static SelfTest of(String name, Consumer<SelfTestRun> checks)
	{
		return new SelfTest()
		{
			@Override
			public String name()
			{
				return name;
			}

			@Override
			public void run(SelfTestRun run)
			{
				checks.accept(run);
				run.done();
			}
		};
	}
}
