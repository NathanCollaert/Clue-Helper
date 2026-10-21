package com.cluehelper.transport;

import java.util.List;
import lombok.Value;

/** Satisfied by any one of its branches; a branch's ids are interchangeable. */
@Value
public class ItemClause
{
	List<Branch> branches;
	boolean mustBeWorn;
	VarCheck waivedBy;

	@Value
	public static class Branch
	{
		List<Integer> ids;
		int quantity;
		/** The name after {@code UNLOCK_}; null for an item branch. */
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
