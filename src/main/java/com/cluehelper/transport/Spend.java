package com.cluehelper.transport;

import java.util.*;
import lombok.Value;

@Value
public class Spend
{
	public enum Kind
	{
		RUNES, ITEM, CHARGE, COOLDOWN, DAILY, CAPE_USE
	}

	private static final int NO_VAR = -1;
	private static final String NONE = "none";

	Kind kind;
	/** The cooldown varp or the daily counter varbit; {@link #NO_VAR} for the other kinds. */
	int var;

	static List<Spend> parse(String cell)
	{
		List<Spend> spends = new ArrayList<>();
		for (String clause : RequirementParser.clauses(cell))
		{
			if (clause.equals(NONE))
			{
				continue;
			}
			String[] parts = clause.split(":", 2);
			Kind kind = Kind.valueOf(parts[0].toUpperCase(Locale.ROOT).replace('-', '_'));
			spends.add(new Spend(kind, parts.length == 2 ? Integer.parseInt(parts[1]) : NO_VAR));
		}
		return List.copyOf(spends);
	}
}
