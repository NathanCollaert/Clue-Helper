package com.cluehelper.player;

import java.util.*;
import java.util.regex.*;
import javax.inject.*;
import net.runelite.api.Client;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;

/** Client thread only. The {@code CHARGES_*_QUANTITY} varbits are never a source: they always read 0. */
@Singleton
public class ChargeReader
{
	private static final String ITEM_CHARGES_GROUP = "itemCharge";
	private static final Pattern CHARGES_IN_NAME = Pattern.compile("\\s?\\(t?(\\d+)\\)$");
	private static final int NOT_IN_NAME = -1;

	private static final Map<Integer, Integer> CHARGE_VARBITS = Map.of(
		ItemID.VEOS_KHAREDSTS_MEMOIRS, VarbitID.VEOS_MEMOIR_CHARGES,
		ItemID.BOOK_OF_THE_DEAD, VarbitID.VEOS_MEMOIR_CHARGES);
	private static final Map<Integer, String> ITEM_CHARGES_KEYS = Map.of(ItemID.CHRONICLE, "chronicle");

	@Inject
	private Client client;
	@Inject
	private ConfigManager configManager;
	private final Map<Integer, Integer> chargesInName = new HashMap<>();

	static Collection<Integer> chargeVarbits()
	{
		return CHARGE_VARBITS.values();
	}

	Map<Integer, Charge> read(Collection<Integer> heldIds)
	{
		Map<Integer, Charge> charges = new HashMap<>();
		for (int id : heldIds)
		{
			Charge charge = read(id);
			if (charge != null)
			{
				charges.put(id, charge);
			}
		}
		return charges;
	}

	private Charge read(int id)
	{
		int inName = chargesInName.computeIfAbsent(id, this::chargesInName);
		if (inName != NOT_IN_NAME)
		{
			return new Charge(inName);
		}
		Integer varbit = CHARGE_VARBITS.get(id);
		if (varbit != null)
		{
			return new Charge(client.getVarbitValue(varbit));
		}
		String key = ITEM_CHARGES_KEYS.get(id);
		Integer fromPlugin = key == null ? null : configManager.getRSProfileConfiguration(ITEM_CHARGES_GROUP, key, Integer.class);
		return fromPlugin == null ? null : new Charge(fromPlugin);
	}

	private int chargesInName(int id)
	{
		Matcher m = CHARGES_IN_NAME.matcher(client.getItemDefinition(id).getName());
		return m.find() ? Integer.parseInt(m.group(1)) : NOT_IN_NAME;
	}
}
