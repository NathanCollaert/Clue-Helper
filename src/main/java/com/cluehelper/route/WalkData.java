package com.cluehelper.route;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.concurrent.*;
import javax.inject.*;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class WalkData
{
	@Value
	public static class Loaded
	{
		WalkTable table;
		NoTeleportAreas noTeleport;
		WorldMap map;
	}

	private final CompletableFuture<Loaded> loaded;

	@Inject
	WalkData(ScheduledExecutorService executor, Gson gson)
	{
		loaded = CompletableFuture.supplyAsync(() -> loadOrNull(gson), executor);
	}

	public CompletableFuture<Loaded> get()
	{
		return loaded;
	}

	private static Loaded loadOrNull(Gson gson)
	{
		try
		{
			return new Loaded(WalkTable.load(gson), NoTeleportAreas.load(), WorldMap.load());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("the bundled walk data is unreadable: no routes will be planned", e);
			return null;
		}
	}
}
