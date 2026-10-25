package com.cluehelper.route;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
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
	WalkData(ScheduledExecutorService executor)
	{
		loaded = CompletableFuture.supplyAsync(WalkData::loadOrNull, executor);
	}

	public CompletableFuture<Loaded> get()
	{
		return loaded;
	}

	private static Loaded loadOrNull()
	{
		try
		{
			long started = System.nanoTime();
			Loaded data = new Loaded(WalkTable.load(), NoTeleportAreas.load(), WorldMap.load());
			log.debug("walk data: {} nodes, {} entries, {} rows in {} ms", data.getTable().nodeCount(), data.getTable().entryCount(),
				data.getTable().rowCount(), (System.nanoTime() - started) / 1_000_000);
			return data;
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("the bundled walk data is unreadable: no routes will be planned", e);
			return null;
		}
	}
}
