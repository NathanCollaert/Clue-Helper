package com.cluehelper.transport;

import java.util.List;
import lombok.Value;

/**
 * One {@code &}-part of an Items cell: satisfied by any one of its {@code |}-branches. A branch is a
 * set of interchangeable item ids with one quantity, or an unlock token.
 */
@Value
public class ItemClause
{
	List<Branch> branches;
	/** Only worn items count, e.g. the fairy-ring staff. */
	boolean mustBeWorn;
	/** The whole clause is waived while this passes, e.g. the Lumbridge elite diary for the fairy-ring staff. */
	VarCheck waivedBy;

	@Value
	public static class Branch
	{
		List<Integer> ids;
		int quantity;
		/** Shortest Path's {@code UNLOCK_<NAME>} token; null for an item branch. */
		String unlock;

		public boolean isUnlock()
		{
			return unlock != null;
		}
	}

	public ItemClause wornOnly(VarCheck waiver)
	{
		return new ItemClause(branches, true, waiver);
	}
}
