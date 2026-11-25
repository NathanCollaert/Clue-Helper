package com.cluehelper;

import java.util.concurrent.*;
import javax.inject.Singleton;

/**
 * The plugin's own thread for loading its data, planning and searching walks. RuneLite's shared executor runs other
 * plugins' tasks too, and one that blocks it (a hiscores request timing out) held a plan back for twenty seconds. One
 * thread keeps every job in the order it was submitted. Runs while the plugin does.
 */
@Singleton
public class BackgroundThread implements Executor
{
	private static final String NAME = "clue-helper";

	volatile ExecutorService thread = newThread();

	void start()
	{
		if (thread.isShutdown())
		{
			thread = newThread();
		}
	}

	void stop()
	{
		thread.shutdownNow();
	}

	@Override
	public void execute(Runnable job)
	{
		thread.execute(job);
	}

	private static ExecutorService newThread()
	{
		return Executors.newSingleThreadExecutor(job ->
		{
			Thread thread = new Thread(job, NAME);
			thread.setDaemon(true);
			return thread;
		});
	}
}
