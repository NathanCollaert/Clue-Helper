package com.cluehelper.transport;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class CatalogData
{
	/** Baked by the test source set's DataBake from the tables Clue-Helper-Tools/tools/catalog/seed.py writes. */
	static final String FILE = "catalog.json";

	private final CompletableFuture<Catalog> catalog;

	@Inject
	CatalogData(ScheduledExecutorService executor, Gson gson)
	{
		catalog = CompletableFuture.supplyAsync(() -> loadOrEmpty(gson), executor);
	}

	public static Catalog read(Gson gson)
	{
		try (InputStream in = CatalogData.class.getResourceAsStream(FILE))
		{
			if (in == null)
			{
				throw new IllegalStateException(FILE + " is missing");
			}
			return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Catalog.class);
		}
		catch (IOException e)
		{
			throw new IllegalStateException("can't read " + FILE, e);
		}
	}

	public CompletableFuture<Catalog> get()
	{
		return catalog;
	}

	private static Catalog loadOrEmpty(Gson gson)
	{
		try
		{
			return read(gson);
		}
		catch (IllegalStateException | JsonParseException e)
		{
			log.warn("the bundled catalog is unreadable: no teleports will be offered", e);
			return Catalog.EMPTY;
		}
	}
}
