package com.cluehelper.route;

import static org.junit.Assert.assertTrue;

import com.cluehelper.player.Charge;
import com.cluehelper.player.Container;
import com.cluehelper.player.ItemUse;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.RowAvailability;
import com.cluehelper.transport.Spend;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The golden run's first invariant, tallied from a route's legs rather than the search's labels: nothing is
 * spent beyond what the player has (items and runes, charges, a cooldown or daily teleport once, cape uses).
 */
final class SpendCheck
{
	private SpendCheck()
	{
	}

	static void assertWithinMeans(PlanInput input, Route route)
	{
		Map<String, Integer> used = new HashMap<>();
		PlayerSnapshot at = input.getPlayer();
		for (Leg leg : route.getLegs())
		{
			if (leg.getKind() == Leg.Kind.BANK)
			{
				at = input.getBanked();
				continue;
			}
			if (leg.getRow() != null)
			{
				tally(input, leg.getRow(), leg.getRow().getSpends(), at, used);
			}
			if (leg.getChange() != null)
			{
				CatalogRow asRow = CatalogRow.builder().name(leg.getChange().getName()).requirements(leg.getChange().getRequirements()).build();
				tally(input, asRow, leg.getChange().getSpends(), at, used);
			}
		}
		PlayerSnapshot end = at;
		used.forEach((key, units) -> assertTrue(input.getStep().getKey() + ": " + route.describe() + " uses " + units + " of " + key
			+ ", has " + available(key, end), units <= available(key, end)));
	}

	private static void tally(PlanInput input, CatalogRow row, List<Spend> spends, PlayerSnapshot player, Map<String, Integer> used)
	{
		RowAvailability judged = input.getEngine().judge(row, player.toBuilder().wildernessLevel(0).build(), input.getNowMinutes());
		for (Spend spend : spends)
		{
			switch (spend.getKind())
			{
				case RUNES:
				case ITEM:
					for (ItemUse use : judged.getWitness())
					{
						if (use.getContainer() != Container.WORN)
						{
							used.merge("item:" + use.getItemId(), use.getQuantity(), Integer::sum);
						}
					}
					break;
				case CHARGE:
					judged.getWitness().forEach(use -> used.merge("charge:" + use.getItemId(), 1, Integer::sum));
					break;
				case COOLDOWN:
				case DAILY:
					used.merge("once:" + spend.getVar(), 1, Integer::sum);
					break;
				default:
					used.merge("cape", 1, Integer::sum);
			}
		}
	}

	private static int available(String key, PlayerSnapshot player)
	{
		String[] parts = key.split(":");
		switch (parts[0])
		{
			case "item":
				int id = Integer.parseInt(parts[1]);
				return player.count(Container.INVENTORY, id) + player.count(Container.WORN, id) + player.count(Container.RUNE_POUCH, id);
			case "charge":
				int item = Integer.parseInt(parts[1]);
				Charge charge = player.getCharges().get(item);
				int held = player.count(Container.INVENTORY, item) + player.count(Container.WORN, item);
				return Math.max(1, held) * (charge == null ? 1 : charge.getCharges());
			case "once":
				return 1;
			default:
				return player.getSpellbook().getCapeChangesLeft();
		}
	}
}
