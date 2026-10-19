package com.cluehelper.dev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

public class SelfTestRunnerTest
{
	private static final long TIMEOUT_MS = 200;

	private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1);
	private final SelfTestRunner runner = new SelfTestRunner(Runnable::run, scheduler, TIMEOUT_MS);
	private final AtomicReference<SelfTestReport> result = new AtomicReference<>();
	private final CountDownLatch finished = new CountDownLatch(1);

	@After
	public void stopScheduler()
	{
		scheduler.shutdownNow();
	}

	@Test
	public void countsChecksAndListsFailures() throws InterruptedException
	{
		runner.register(SelfTest.of("good", run ->
		{
			run.check(true, "a");
			run.check(true, "b");
		}));
		runner.register(SelfTest.of("bad", run -> run.check(false, "c")));
		runner.register(SelfTest.of("logged out", run -> run.skip("needs a login")));

		SelfTestReport report = runToEnd();

		assertEquals(2, report.passed());
		assertEquals(1, report.failed());
		assertEquals(1, report.skipped());
		assertEquals(List.of("bad: c"), report.failures());
	}

	@Test
	public void aThrowingTestFailsAndTheRunCarriesOn() throws InterruptedException
	{
		runner.register(SelfTest.of("throws", run ->
		{
			throw new IllegalStateException("boom");
		}));
		runner.register(SelfTest.of("after", run -> run.check(true, "still runs")));

		SelfTestReport report = runToEnd();

		assertEquals(1, report.passed());
		assertEquals(1, report.failed());
		assertTrue(report.failures().get(0).startsWith("throws: threw java.lang.IllegalStateException"));
	}

	@Test
	public void aTestThatNeverFinishesTimesOut() throws InterruptedException
	{
		runner.register(new SelfTest()
		{
			@Override
			public String name()
			{
				return "hangs";
			}

			@Override
			public void run(SelfTestRun run)
			{
				run.check(true, "started");
			}
		});
		runner.register(SelfTest.of("next", run -> run.check(true, "runs after the timeout")));

		SelfTestReport report = runToEnd();

		assertEquals(2, report.passed());
		assertEquals(List.of("hangs: timed out after " + TIMEOUT_MS + " ms"), report.failures());
	}

	@Test
	public void anAsyncTestFinishesFromAnotherThread() throws InterruptedException
	{
		runner.register(new SelfTest()
		{
			@Override
			public String name()
			{
				return "async";
			}

			@Override
			public void run(SelfTestRun run)
			{
				scheduler.schedule(() ->
				{
					run.check(true, "checked later");
					run.done();
				}, 20, TimeUnit.MILLISECONDS);
			}
		});

		SelfTestReport report = runToEnd();

		assertEquals(1, report.passed());
		assertEquals(0, report.failed());
	}

	@Test
	public void aTestWithNoChecksFails() throws InterruptedException
	{
		runner.register(SelfTest.of("empty", run ->
		{
		}));
		assertEquals(List.of("empty: made no checks"), runToEnd().failures());
	}

	@Test
	public void oneRunAtATimeAndUnregisterRemovesATest() throws InterruptedException
	{
		List<String> order = new ArrayList<>();
		Runnable unregister = runner.register(SelfTest.of("removed", run -> order.add("removed")));
		runner.register(new SelfTest()
		{
			@Override
			public String name()
			{
				return "slow";
			}

			@Override
			public void run(SelfTestRun run)
			{
				order.add("slow");
				assertFalse("a second run is refused", runner.run(r -> order.add("second run")));
				scheduler.schedule(() ->
				{
					run.check(true, "done");
					run.done();
				}, 20, TimeUnit.MILLISECONDS);
			}
		});
		unregister.run();

		runToEnd();

		assertEquals(List.of("slow"), order);
	}

	@Test
	public void runsInAscendingOrderThenRegistrationOrder() throws InterruptedException
	{
		List<String> order = new ArrayList<>();
		runner.register(ordered("last", 1000, order));
		runner.register(ordered("first", 0, order));
		runner.register(ordered("second", 0, order));
		runner.register(ordered("middle", 100, order));

		runToEnd();

		assertEquals(List.of("first", "second", "middle", "last"), order);
	}

	private static SelfTest ordered(String name, int order, List<String> ran)
	{
		return new SelfTest()
		{
			@Override
			public String name()
			{
				return name;
			}

			@Override
			public int order()
			{
				return order;
			}

			@Override
			public void run(SelfTestRun run)
			{
				ran.add(name);
				run.check(true, "ran");
				run.done();
			}
		};
	}

	private SelfTestReport runToEnd() throws InterruptedException
	{
		assertTrue(runner.run(report ->
		{
			result.set(report);
			finished.countDown();
		}));
		assertTrue("run finished", finished.await(5, TimeUnit.SECONDS));
		return result.get();
	}
}
