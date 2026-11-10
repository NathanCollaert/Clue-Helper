package com.cluehelper.transport;

import com.cluehelper.player.PlayerSnapshot;
import java.util.Locale;
import java.util.function.ToIntFunction;
import lombok.*;
import net.runelite.api.Skill;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LevelNeed
{
	@RequiredArgsConstructor
	public enum Aggregate
	{
		TOTAL("%d total level", PlayerSnapshot::getTotalLevel),
		COMBAT("combat level %d", PlayerSnapshot::getCombatLevel),
		QUEST_POINTS("%d quest points", PlayerSnapshot::getQuestPoints);

		private final String format;
		private final ToIntFunction<PlayerSnapshot> have;
	}

	/** Null for an aggregate. */
	Skill skill;
	/** Null for a skill. */
	Aggregate aggregate;
	int level;

	public static LevelNeed ofSkill(Skill skill, int level)
	{
		return new LevelNeed(skill, null, level);
	}

	public static LevelNeed ofAggregate(Aggregate aggregate, int level)
	{
		return new LevelNeed(null, aggregate, level);
	}

	/**
	 * The player's own level the need is held against: boosts count, but Hitpoints' boosted level is the health left,
	 * and a Hitpoints need (the God Wars ice bridge's 70) is on the level itself.
	 */
	public int have(PlayerSnapshot player)
	{
		if (skill == Skill.HITPOINTS)
		{
			return Math.max(player.real(skill), player.boosted(skill));
		}
		return skill != null ? player.boosted(skill) : aggregate.have.applyAsInt(player);
	}

	public String describe()
	{
		return skill != null ? level + " " + skill.getName() : String.format(Locale.ROOT, aggregate.format, level);
	}
}
