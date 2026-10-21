package com.cluehelper.transport;

import java.util.List;
import lombok.Value;

@Value
public class Catalog
{
	public static final Catalog EMPTY = new Catalog(List.of(), List.of());

	List<CatalogRow> teleports;
	List<SpellbookChange> spellbookChanges;
}
