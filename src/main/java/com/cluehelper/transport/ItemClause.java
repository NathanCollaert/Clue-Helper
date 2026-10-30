package com.cluehelper.transport;

import java.util.List;
import lombok.Value;
import net.runelite.api.Quest;

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
		/** The quest after {@code QUEST_} whose finishing does without the items (Ghosts Ahoy, Port Phasmatys' toll). */
		Quest quest;

		public boolean isUnlock()
		{
			return unlock != null;
		}

		public boolean isQuest()
		{
			return quest != null;
		}
	}

	public ItemClause wornOnly(VarCheck waiver)
	{
		return new ItemClause(branches, true, waiver);
	}
}
