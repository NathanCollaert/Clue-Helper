package com.cluehelper.guide;

import com.cluehelper.step.Step;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;

/**
 * The prayer book's filters as a step found them, so a Sherlock prayer task that had to change them can set them back.
 * Client thread only.
 */
@Singleton
public class PrayerFilters
{
	/** Each filter's varbit and a phrase of its line in the filter menu. */
	public enum Filter
	{
		LOWER_TIERS(VarbitID.PRAYER_FILTER_BLOCKLOWTIER, "lower tiers", 1),
		MULTI_SKILL(VarbitID.PRAYER_FILTER_ALLOWCOMBINEDTIER, "multi-skill", 0),
		RAPID_HEALING(VarbitID.PRAYER_FILTER_BLOCKHEALING, "rapid healing", 1),
		LACK_LEVEL(VarbitID.PRAYER_FILTER_BLOCKLACKLEVEL, "prayer level", 1),
		LACK_REQUIREMENTS(VarbitID.PRAYER_FILTER_BLOCKLOCKED, "requirements", 1);

		private final int varbit;
		private final String phrase;
		private final int hidingValue;

		Filter(int varbit, String phrase, int hidingValue)
		{
			this.varbit = varbit;
			this.phrase = phrase;
			this.hidingValue = hidingValue;
		}

		public String getPhrase()
		{
			return phrase;
		}
	}

	/** Only these can hide a prayer the player could turn on: the level and requirement filters hide ones they can't. */
	private static final List<Filter> TIERS = List.of(Filter.LOWER_TIERS, Filter.MULTI_SKILL);

	private final Client client;
	private String stepKey;
	private Map<Filter, Integer> found = Map.of();

	@Inject
	PrayerFilters(Client client)
	{
		this.client = client;
	}

	/** Notes the filters the first time a step is seen, before its task has anyone change them. */
	public void rememberFor(Step step)
	{
		if (step == null || step.getKey().equals(stepKey))
		{
			return;
		}
		stepKey = step.getKey();
		Map<Filter, Integer> now = new EnumMap<>(Filter.class);
		for (Filter filter : Filter.values())
		{
			now.put(filter, client.getVarbitValue(filter.varbit));
		}
		found = Map.copyOf(now);
	}

	/** The tier filters hiding prayers now. */
	public List<Filter> hidingTiers()
	{
		return TIERS.stream().filter(filter -> client.getVarbitValue(filter.varbit) == filter.hidingValue)
			.collect(Collectors.toUnmodifiableList());
	}

	/** The filters set otherwise than the step found them. */
	public List<Filter> changed()
	{
		return found.entrySet().stream().filter(entry -> client.getVarbitValue(entry.getKey().varbit) != entry.getValue())
			.map(Map.Entry::getKey).sorted().collect(Collectors.toUnmodifiableList());
	}
}
