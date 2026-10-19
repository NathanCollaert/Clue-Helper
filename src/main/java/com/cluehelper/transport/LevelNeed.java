package com.cluehelper.transport;

import lombok.Value;
import net.runelite.api.Skill;

/** One clause of a Skills cell: a boosted skill level, or total level, combat level or quest points. */
@Value
public class LevelNeed
{
	public enum Aggregate
	{
		TOTAL, COMBAT, QUEST_POINTS
	}

	/** Null for an aggregate. */
	Skill skill;
	/** Null for a skill. */
	Aggregate aggregate;
	int level;

	public String describe()
	{
		if (skill != null)
		{
			return level + " " + skill.getName();
		}
		if (aggregate == Aggregate.TOTAL)
		{
			return level + " total level";
		}
		return aggregate == Aggregate.COMBAT ? "combat level " + level : level + " quest points";
	}
}
