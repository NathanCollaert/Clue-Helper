package com.cluehelper.transport;

import java.util.concurrent.*;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class CatalogData
{
	private final CompletableFuture<Catalog> catalog;

	@Inject
	CatalogData(ScheduledExecutorService executor)
	{
		catalog = CompletableFuture.supplyAsync(CatalogData::loadOrEmpty, executor);
	}

	public CompletableFuture<Catalog> get()
	{
		return catalog;
	}

	private static Catalog loadOrEmpty()
	{
		try
		{
			long started = System.nanoTime();
			Catalog loaded = CatalogLoader.load();
			log.debug("catalog: {} teleports, {} spellbook changes in {} ms", loaded.getTeleports().size(),
				loaded.getSpellbookChanges().size(), (System.nanoTime() - started) / 1_000_000);
			return loaded;
		}
		catch (IllegalStateException e)
		{
			log.warn("the bundled catalog is unreadable: no teleports will be offered", e);
			return Catalog.EMPTY;
		}
	}
}
