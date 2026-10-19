package com.cluehelper.transport;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** One teleport or transport. B09 adds the catalog's other columns (category, duration, menu ticks, spends). */
@Value
public class CatalogRow
{
	/** Shortest Path's Display info. */
	String name;
	/** Null for a row that goes nowhere fixed. */
	WorldPoint destination;
	Requirements requirements;
}
