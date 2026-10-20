package com.cluehelper.transport;

import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;

/**
 * The house piece a POH row needs (catalog House column, B16): {@code object:29154|29155|29156} is any of these
 * furniture base ids, {@code nexus:14} a portal nexus slot set to that destination (enum 1377 key).
 */
@Value
public class HouseNeed
{
	public enum Kind
	{
		FURNITURE, NEXUS
	}

	Kind kind;
	/** Furniture base ids (any of them), or the one nexus key. */
	List<Integer> ids;

	public String describe()
	{
		return kind == Kind.NEXUS ? "nexus destination " + ids.get(0)
			: "house object " + ids.stream().map(String::valueOf).collect(Collectors.joining(" or "));
	}
}
