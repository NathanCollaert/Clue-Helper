package com.cluehelper.transport;

import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;

@Value
public class HouseNeed
{
	public enum Kind
	{
		FURNITURE, NEXUS
	}

	Kind kind;
	/** Furniture base ids, any of which will do, or the one nexus destination key (enum 1377). */
	List<Integer> ids;

	public String describe()
	{
		return kind == Kind.NEXUS ? "nexus destination " + ids.get(0)
			: "house object " + ids.stream().map(String::valueOf).collect(Collectors.joining(" or "));
	}
}
