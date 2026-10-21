package com.cluehelper.dev;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;

/** A test that throws or never finishes fails, and the run carries on. */
@Slf4j
@Singleton
public class SelfTestRunner
{
	static final String TAG = "[CH-TEST]";
	private static final long TIMEOUT_MS = 15_000;

	private final Executor clientThread;
	private final ScheduledExecutorService scheduler;
	private final long timeoutMs;
	private final List<SelfTest> tests = new CopyOnWriteArrayList<>();
	private final AtomicBoolean running = new AtomicBoolean();

	@Inject
	SelfTestRunner(ClientThread clientThread, ScheduledExecutorService scheduler)
	{
		this(clientThread::invoke, scheduler, TIMEOUT_MS);
	}

	SelfTestRunner(Executor clientThread, ScheduledExecutorService scheduler, long timeoutMs)
	{
		this.clientThread = clientThread;
		this.scheduler = scheduler;
		this.timeoutMs = timeoutMs;
	}

	/** @return an action that unregisters the test again */
	public Runnable register(SelfTest test)
	{
		tests.add(test);
		return () -> tests.remove(test);
	}

	public List<String> testNames()
	{
		List<String> names = new ArrayList<>();
		for (SelfTest test : tests)
		{
			names.add(test.name());
		}
		return names;
	}

	/**
	 * @param onFinished called on the client thread with the totals
	 * @return false, doing nothing, while an earlier run is still going
	 */
	public boolean run(Consumer<SelfTestReport> onFinished)
	{
		if (!running.compareAndSet(false, true))
		{
			return false;
		}
		List<SelfTest> ordered = new ArrayList<>(tests);
		ordered.sort(Comparator.comparingInt(SelfTest::order));
		Deque<SelfTest> queue = new ArrayDeque<>(ordered);
		SelfTestReport report = new SelfTestReport();
		log.info("{} running {} tests", TAG, queue.size());
		clientThread.execute(() -> runNext(queue, report, onFinished));
		return true;
	}

	private void runNext(Deque<SelfTest> queue, SelfTestReport report, Consumer<SelfTestReport> onFinished)
	{
		SelfTest test = queue.poll();
		if (test == null)
		{
			log.info("{} RESULT: {}", TAG, report);
			running.set(false);
			onFinished.accept(report);
			return;
		}
		log.info("{} test: {}", TAG, test.name());
		Run run = new Run(test.name(), report, () -> clientThread.execute(() -> runNext(queue, report, onFinished)));
		run.timeout = scheduler.schedule(run::timeOut, timeoutMs, TimeUnit.MILLISECONDS);
		try
		{
			test.run(run);
		}
		catch (RuntimeException | AssertionError e)
		{
			log.warn("{} {} threw", TAG, test.name(), e);
			run.check(false, "threw " + e);
			run.done();
		}
	}

	private final class Run implements SelfTestRun
	{
		private final String test;
		private final SelfTestReport report;
		private final Runnable next;
		private final AtomicBoolean finished = new AtomicBoolean();
		private final AtomicInteger checks = new AtomicInteger();
		private volatile boolean skipped;
		private volatile ScheduledFuture<?> timeout;

		Run(String test, SelfTestReport report, Runnable next)
		{
			this.test = test;
			this.report = report;
			this.next = next;
		}

		@Override
		public void check(boolean ok, String what)
		{
			if (finished.get())
			{
				log.warn("{} {}: check after the test ended, ignored: {}", TAG, test, what);
				return;
			}
			checks.incrementAndGet();
			report.record(test, ok, what);
			log.info("{} {} {}: {}", TAG, ok ? "PASS" : "FAIL", test, what);
		}

		@Override
		public void skip(String why)
		{
			if (!skipped && !finished.get())
			{
				skipped = true;
				report.skip();
				log.info("{} SKIP {}: {}", TAG, test, why);
			}
		}

		@Override
		public void done()
		{
			if (!finished.compareAndSet(false, true))
			{
				return;
			}
			ScheduledFuture<?> t = timeout;
			if (t != null)
			{
				t.cancel(false);
			}
			if (checks.get() == 0 && !skipped)
			{
				report.record(test, false, "made no checks");
				log.info("{} FAIL {}: made no checks", TAG, test);
			}
			next.run();
		}

		void timeOut()
		{
			if (!finished.get())
			{
				check(false, "timed out after " + timeoutMs + " ms");
				done();
			}
		}
	}
}
