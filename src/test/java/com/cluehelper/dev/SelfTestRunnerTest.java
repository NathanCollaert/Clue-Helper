package com.cluehelper.dev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Test;

public class SelfTestRunnerTest
{
	private static final long TIMEOUT_MS = 200;

	private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1);
	private final SelfTestRunner runner = new SelfTestRunner(Runnable::run, scheduler, TIMEOUT_MS);

	@After
	public void stopScheduler()
	{
		scheduler.shutdownNow();
	}

	@Test
	public void aFailingThrowingOrHangingTestIsReportedAndTheRunCarriesOn() throws InterruptedException
	{
		List<String> ran = new ArrayList<>();
		runner.register(test("passes", 0, ran, run -> run.check(true, "ok")));
		runner.register(test("fails", 0, ran, run -> run.check(false, "wrong")));
		runner.register(test("skips", 0, ran, run -> run.skip("logged out")));
		runner.register(test("throws", 0, ran, run ->
		{
			throw new IllegalStateException("boom");
		}));
		runner.register(test("hangs", 0, ran, null));
		runner.register(test("runs after the hang", 0, ran, run -> run.check(true, "ok")));

		SelfTestReport report = runToEnd();

		assertEquals(List.of("passes", "fails", "skips", "throws", "hangs", "runs after the hang"), ran);
		assertEquals(2, report.passed());
		assertEquals(1, report.skipped());
		assertEquals(3, report.failed());
		assertTrue(report.failures().get(1).startsWith("throws: threw java.lang.IllegalStateException"));
		assertEquals("hangs: timed out after " + TIMEOUT_MS + " ms", report.failures().get(2));
	}

	@Test
	public void runsInAscendingOrderThenRegistrationOrder() throws InterruptedException
	{
		List<String> ran = new ArrayList<>();
		runner.register(test("last", 1000, ran, run -> run.check(true, "ok")));
		runner.register(test("first", 0, ran, run -> run.check(true, "ok")));
		runner.register(test("second", 0, ran, run -> run.check(true, "ok")));
		runner.register(test("middle", 100, ran, run -> run.check(true, "ok")));

		runToEnd();

		assertEquals(List.of("first", "second", "middle", "last"), ran);
	}

	/** A null body never calls done(), so the runner's timeout has to end it. */
	private static SelfTest test(String name, int order, List<String> ran, Consumer<SelfTestRun> body)
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
				if (body != null)
				{
					body.accept(run);
					run.done();
				}
			}
		};
	}

	private SelfTestReport runToEnd() throws InterruptedException
	{
		AtomicReference<SelfTestReport> result = new AtomicReference<>();
		CountDownLatch finished = new CountDownLatch(1);
		assertTrue(runner.run(report ->
		{
			result.set(report);
			finished.countDown();
		}));
		assertTrue("run finished", finished.await(5, TimeUnit.SECONDS));
		return result.get();
	}
}
