package com.cluehelper.poh;

import com.cluehelper.ClueHelperConfig;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ObjectID;

final class PohOverrides
{
	private static final int[] BOXES = {ObjectID.POH_JEWELLERY_BOX_1, ObjectID.POH_JEWELLERY_BOX_2, ObjectID.POH_JEWELLERY_BOX_3};
	/** Box lines 1–9 are on every box, A–J from the fancy one, K–R only on the ornate one; varbit 2308 is 1-based. */
	private static final int FANCY_FIRST_LINE = 10;
	private static final int ORNATE_FIRST_LINE = 20;
	private static final int XERIC_FIRST = ObjectID.POH_AMULET_XERIC_LOOKOUT;
	private static final int DIGSITE_FIRST = ObjectID.POH_AMULET_DIG_DIGSITE;

	private PohOverrides()
	{
	}

	static Set<Integer> furniture(ClueHelperConfig config, Map<String, List<Integer>> portalsByName)
	{
		Set<Integer> ids = new HashSet<>();
		switch (config.pohJewelleryBox())
		{
			case BASIC:
				ids.add(ObjectID.POH_JEWELLERY_BOX_1);
				break;
			case FANCY:
				ids.add(ObjectID.POH_JEWELLERY_BOX_2);
				break;
			case ORNATE:
				ids.add(ObjectID.POH_JEWELLERY_BOX_3);
				break;
			default:
				break;
		}
		if (config.pohMountedGlory())
		{
			ids.add(ObjectID.POH_TROPHY_AMULETOFGLORY_4);
		}
		switch (config.pohGarden())
		{
			case SPIRIT_TREE:
				ids.add(ObjectID.POH_SPIRIT_TREE);
				break;
			case FAIRY_RING:
				ids.add(ObjectID.POH_FAIRY_RING);
				break;
			case SPIRITUAL_FAIRY_TREE:
				ids.add(ObjectID.POH_SPIRIT_RING);
				break;
			default:
				break;
		}
		if (config.pohObelisk())
		{
			ids.add(ObjectID.POH_WILDERNESS_OBELISK);
		}
		switch (config.pohAltar())
		{
			case OCCULT:
				ids.add(ObjectID.POH_ALTAR_OCCULT);
				break;
			case ANCIENT:
				ids.add(ObjectID.POH_ALTAR_ANCIENT);
				break;
			case LUNAR:
				ids.add(ObjectID.POH_ALTAR_LUNAR);
				break;
			case DARK:
				ids.add(ObjectID.POH_ALTAR_DARK);
				break;
			default:
				break;
		}
		switch (config.pohMountedCape())
		{
			case MAGIC:
				ids.add(ObjectID.POH_MOUNTED_MAGIC_CAPE);
				break;
			case MAGIC_TRIMMED:
				ids.add(ObjectID.POH_MOUNTED_MAGIC_CAPE_TRIM);
				break;
			case MAX:
				ids.add(ObjectID.POH_MOUNTED_MAX_CAPE);
				break;
			default:
				break;
		}
		if (config.pohMythicalCape())
		{
			ids.add(ObjectID.POH_TROPHY_MYTHICAL_CAPE);
		}
		for (String name : config.pohPortals().split(","))
		{
			List<Integer> portal = portalsByName.get(normalise(name));
			if (portal != null)
			{
				ids.addAll(portal);
			}
		}
		return ids;
	}

	/**
	 * The mounted Xeric's talisman and digsite pendant follow from their left-click settings, and the jewellery box from
	 * its left-click line, which needs at least the tier that has that line.
	 */
	static Set<Integer> fromVarbits(int xeric, int digsite, int boxLine)
	{
		Set<Integer> ids = new HashSet<>();
		if (xeric > 0)
		{
			ids.add(XERIC_FIRST + xeric - 1);
		}
		if (digsite > 0)
		{
			ids.add(DIGSITE_FIRST + digsite - 1);
		}
		if (boxLine > 0)
		{
			ids.add(BOXES[boxLine >= ORNATE_FIRST_LINE ? 2 : boxLine >= FANCY_FIRST_LINE ? 1 : 0]);
		}
		return ids;
	}

	static String normalise(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT);
	}
}
