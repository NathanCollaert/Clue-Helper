package com.cluehelper.step;

import com.google.gson.Gson;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Loads the bundled step data once, off the Swing and client threads. */
@Singleton
public class StepData
{
	private final CompletableFuture<StepIndex> index;

	@Inject
	StepData(Gson gson, ScheduledExecutorService executor)
	{
		index = CompletableFuture.supplyAsync(() -> StepIndex.read(gson), executor);
	}

	/** Waits for the load the first time, if a step arrives within the first moments of a start. */
	public StepIndex index()
	{
		return index.join();
	}
}
