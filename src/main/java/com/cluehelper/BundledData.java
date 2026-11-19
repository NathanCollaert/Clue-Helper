package com.cluehelper;

import com.cluehelper.route.WalkData;
import com.cluehelper.step.StepIndex;
import com.cluehelper.transport.Catalog;
import com.google.gson.*;
import java.io.IOException;
import java.util.concurrent.*;
import javax.inject.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/** The data files the plugin ships, read off the client thread from start-up on. */
@Slf4j
@Singleton
public class BundledData
{
	@Getter
	private final CompletableFuture<Catalog> catalog;
	private final CompletableFuture<StepIndex> steps;
	/** Null inside when the walk data is unreadable: no routes are planned. */
	@Getter
	private final CompletableFuture<WalkData> walk;

	@Inject
	BundledData(ScheduledExecutorService executor, Gson gson)
	{
		catalog = CompletableFuture.supplyAsync(() -> catalogOrEmpty(gson), executor);
		steps = CompletableFuture.supplyAsync(() -> StepIndex.read(gson), executor);
		walk = CompletableFuture.supplyAsync(() -> walkOrNull(gson), executor);
	}

	/** Blocks while the load is still running, which only a step right after start-up can hit. */
	public StepIndex stepIndex()
	{
		return steps.join();
	}

	private static Catalog catalogOrEmpty(Gson gson)
	{
		try
		{
			return Catalog.read(gson);
		}
		catch (IllegalStateException | JsonParseException e)
		{
			log.warn("the bundled catalog is unreadable: no teleports will be offered", e);
			return Catalog.EMPTY;
		}
	}

	private static WalkData walkOrNull(Gson gson)
	{
		try
		{
			return WalkData.load(gson);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("the bundled walk data is unreadable: no routes will be planned", e);
			return null;
		}
	}
}
