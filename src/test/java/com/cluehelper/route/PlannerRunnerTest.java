package com.cluehelper.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;

public class PlannerRunnerTest
{
	private static final class ManualExecutor implements Executor
	{
		final Deque<Runnable> queue = new ArrayDeque<>();

		@Override
		public void execute(Runnable task)
		{
			queue.add(task);
		}

		void runAll()
		{
			while (!queue.isEmpty())
			{
				queue.poll().run();
			}
		}
	}

	private final ManualExecutor background = new ManualExecutor();
	private final ManualExecutor clientThread = new ManualExecutor();
	private final PlannerRunner runner = new PlannerRunner(background, clientThread);
	private final List<String> delivered = new ArrayList<>();
	private final List<String> ran = new ArrayList<>();

	@Test
	public void aJobSupersededBeforeItStartsNeverRuns()
	{
		runner.submit(() -> job("first"), delivered::add);
		runner.submit(() -> job("second"), delivered::add);
		background.runAll();
		clientThread.runAll();

		assertEquals(List.of("second"), ran);
		assertEquals(List.of("second"), delivered);
	}

	@Test
	public void aJobSupersededWhileItRunsHasItsResultDropped()
	{
		runner.submit(() ->
		{
			runner.submit(() -> job("second"), delivered::add);
			return job("first");
		}, delivered::add);
		background.runAll();
		clientThread.runAll();

		assertEquals(List.of("first", "second"), ran);
		assertEquals(List.of("second"), delivered);
	}

	@Test
	public void aResultWaitingForTheClientThreadIsDroppedWhenSuperseded()
	{
		runner.submit(() -> job("first"), delivered::add);
		background.runAll();
		runner.submit(() -> job("second"), delivered::add);
		clientThread.runAll();
		assertTrue(delivered.isEmpty());

		background.runAll();
		clientThread.runAll();
		assertEquals(List.of("second"), delivered);
	}

	@Test
	public void workingUntilTheLatestResultIsDelivered()
	{
		assertFalse(runner.isWorking());
		runner.submit(() -> job("only"), delivered::add);
		assertTrue(runner.isWorking());
		background.runAll();
		assertTrue(runner.isWorking());
		clientThread.runAll();
		assertFalse(runner.isWorking());
	}

	@Test
	public void cancelAllDropsPendingResults()
	{
		runner.submit(() -> job("only"), delivered::add);
		runner.cancelAll();
		assertFalse(runner.isWorking());
		background.runAll();
		clientThread.runAll();
		assertTrue(delivered.isEmpty());
	}

	@Test
	public void aFailingJobLeavesTheRunnerIdle()
	{
		runner.<String>submit(() ->
		{
			throw new IllegalStateException("boom");
		}, delivered::add);
		background.runAll();
		clientThread.runAll();
		assertFalse(runner.isWorking());
		assertTrue(delivered.isEmpty());
	}

	private String job(String name)
	{
		ran.add(name);
		return name;
	}
}
