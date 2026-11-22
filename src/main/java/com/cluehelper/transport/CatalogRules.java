package com.cluehelper.transport;

import com.cluehelper.Tables;
import java.util.*;

public final class CatalogRules
{
	/** Never a teleport item: a glory that shows as 1704 has no charges. */
	public static final Set<Integer> UNCHARGED = Tables.idSet("uncharged");

	private CatalogRules()
	{
	}
}
