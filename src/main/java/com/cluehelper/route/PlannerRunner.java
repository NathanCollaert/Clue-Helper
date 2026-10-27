package com.cluehelper.route;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;

/**
 * Runs planner jobs off the client thread and hands only the latest job's result back to it.
 * Each submit supersedes every earlier one: a superseded job is skipped if it hasn't started,
 * and its result is dropped if it has. Nothing is ever interrupted or slept on.
 */
@Slf4j
@Singleton
public class PlannerRunner
{
	private final Executor background;
	private final Executor clientThread;
	private final AtomicLong generation = new AtomicLong();
	private volatile long finishedGeneration;

	@Inject
	public PlannerRunner(ScheduledExecutorService executor, ClientThread clientThread)
	{
		this(executor, clientThread::invoke);
	}

	PlannerRunner(Executor background, Executor clientThread)
	{
		this.background = background;
		this.clientThread = clientThread;
	}

	/**
	 * @param job reads snapshot data only, never the client
	 * @param onResult runs on the client thread, only if no newer job was submitted meanwhile
	 */
	public <T> void submit(Supplier<T> job, Consumer<T> onResult)
	{
		long id = generation.incrementAndGet();
		background.execute(() -> run(id, job, onResult));
	}

	public void cancelAll()
	{
		finishedGeneration = generation.incrementAndGet();
	}

	public boolean isWorking()
	{
		return finishedGeneration != generation.get();
	}

	private <T> void run(long id, Supplier<T> job, Consumer<T> onResult)
	{
		if (isSuperseded(id))
		{
			return;
		}
		T result;
		try
		{
			result = job.get();
		}
		catch (RuntimeException e)
		{
			log.warn("planner job failed", e);
			finishIfLatest(id);
			return;
		}
		if (isSuperseded(id))
		{
			return;
		}
		clientThread.execute(() ->
		{
			if (finishIfLatest(id))
			{
				onResult.accept(result);
			}
		});
	}

	private boolean isSuperseded(long id)
	{
		return id != generation.get();
	}

	private synchronized boolean finishIfLatest(long id)
	{
		if (isSuperseded(id))
		{
			return false;
		}
		finishedGeneration = id;
		return true;
	}
}
