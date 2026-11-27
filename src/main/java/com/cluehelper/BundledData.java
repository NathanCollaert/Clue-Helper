package com.cluehelper;

import com.cluehelper.route.WalkData;
import com.cluehelper.step.StepIndex;
import com.cluehelper.transport.Catalog;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
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
	final CompletableFuture<Catalog> catalog;
	final CompletableFuture<StepIndex> steps;
	/** Null inside when the walk data is unreadable: no routes are planned. */
	@Getter
	final CompletableFuture<WalkData> walk;

	@Inject
	BundledData(BackgroundThread executor, Gson gson)
	{
		catalog = CompletableFuture.supplyAsync(() -> orElse(() -> Catalog.read(gson), Catalog.EMPTY,
			"catalog is unreadable: no teleports will be offered"), executor);
		steps = CompletableFuture.supplyAsync(() -> orElse(() -> StepIndex.read(gson), StepIndex.empty(),
			"step data is unreadable: every step resolves as unknown"), executor);
		walk = CompletableFuture.supplyAsync(() -> orElse(() -> WalkData.load(gson), null,
			"walk data is unreadable: no routes will be planned"), executor);
	}

	/** Blocks while the load is still running, which only a step right after start-up can hit. */
	public StepIndex stepIndex()
	{
		return steps.join();
	}

	/** A JSON resource beside {@code type}; missing or unreadable is an {@link IllegalStateException}. */
	public static <T> T read(Gson gson, Class<T> type, String file)
	{
		try (InputStream in = type.getResourceAsStream(file))
		{
			if (in == null)
			{
				throw new IllegalStateException(file + " is missing");
			}
			return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
		}
		catch (IOException e)
		{
			throw new IllegalStateException("can't read " + file, e);
		}
	}

	/** Runs on the load thread, so any failure falls back rather than leaving the future failed. */
	private static <T> T orElse(Callable<T> read, T fallback, String unreadable)
	{
		try
		{
			return read.call();
		}
		catch (Exception e)
		{
			log.warn("the bundled {}", unreadable, e);
			return fallback;
		}
	}
}
