package com.cluehelper.transport;

import com.cluehelper.player.PlayerSnapshot;
import lombok.Value;
import net.runelite.api.Skill;

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

	private LevelNeed(Skill skill, Aggregate aggregate, int level)
	{
		this.skill = skill;
		this.aggregate = aggregate;
		this.level = level;
	}

	public static LevelNeed ofSkill(Skill skill, int level)
	{
		return new LevelNeed(skill, null, level);
	}

	public static LevelNeed ofAggregate(Aggregate aggregate, int level)
	{
		return new LevelNeed(null, aggregate, level);
	}

	/** The player's own level the need is held against. */
	public int have(PlayerSnapshot player)
	{
		if (skill != null)
		{
			return player.boosted(skill);
		}
		switch (aggregate)
		{
			case TOTAL:
				return player.getTotalLevel();
			case COMBAT:
				return player.getCombatLevel();
			case QUEST_POINTS:
				return player.getQuestPoints();
			default:
				throw new IllegalArgumentException("unhandled aggregate " + aggregate);
		}
	}

	public String describe()
	{
		if (skill != null)
		{
			return level + " " + skill.getName();
		}
		switch (aggregate)
		{
			case TOTAL:
				return level + " total level";
			case COMBAT:
				return "combat level " + level;
			case QUEST_POINTS:
				return level + " quest points";
			default:
				throw new IllegalArgumentException("unhandled aggregate " + aggregate);
		}
	}
}
