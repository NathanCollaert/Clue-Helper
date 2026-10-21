package com.cluehelper.step;

import com.google.gson.Gson;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class StepData
{
	private final CompletableFuture<StepIndex> index;

	@Inject
	StepData(Gson gson, ScheduledExecutorService executor)
	{
		index = CompletableFuture.supplyAsync(() -> StepIndex.read(gson), executor);
	}

	/** Blocks while the load is still running, which only a step right after start-up can hit. */
	public StepIndex index()
	{
		return index.join();
	}
}
