package com.cluehelper.transport;

import com.cluehelper.player.PlayerSnapshot;
import java.util.Locale;
import java.util.function.ToIntFunction;
import lombok.*;
import net.runelite.api.Skill;

@Value
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class LevelNeed
{
	@RequiredArgsConstructor
	public enum Aggregate
	{
		TOTAL("%d total level", PlayerSnapshot::getTotalLevel, null),
		COMBAT("combat level %d", PlayerSnapshot::getCombatLevel, null),
		QUEST_POINTS("%d quest points", PlayerSnapshot::getQuestPoints, null),
		/** Held against 0: what is still missing counts down to it. */
		MUSIC_TRACKS("every music track", player -> -player.getLockedMusicTracks(), "still locked"),
		ALL_QUEST_POINTS("every quest point", player -> -player.getMissingQuestPoints(), "short");

		private final String format;
		private final ToIntFunction<PlayerSnapshot> have;
		/** How what is missing is worded, for a need held against 0; null for a level. */
		private final String missingAs;
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

	public String shortfall(PlayerSnapshot player)
	{
		boolean countsDown = aggregate != null && aggregate.missingAs != null;
		String has = countsDown ? -have(player) + " " + aggregate.missingAs : "has " + have(player);
		return describe() + " (" + has + ")";
	}
}
