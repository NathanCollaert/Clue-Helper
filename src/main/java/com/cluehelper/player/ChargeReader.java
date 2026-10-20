package com.cluehelper.player;

import com.cluehelper.memory.MemorySlot;
import com.cluehelper.memory.MemoryStore;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;

/**
 * Charges of held items, in PLAN §4 item 4's order: the item id, the memoirs' charge varbit while
 * held, the last value seen while held (remembered per profile), then RuneLite's Item Charges plugin
 * (read only). An item none of them knows has no entry: its charges are unknown. Client thread only.
 * The {@code CHARGES_*_QUANTITY} varbits are never a source: they always read 0 (B13).
 */
@Singleton
public class ChargeReader
{
	static final String MEMORY_KEY = "charges";
	private static final String ITEM_CHARGES_GROUP = "itemCharge";
	private static final Pattern CHARGES_IN_NAME = Pattern.compile("\\s?\\(t?(\\d+)\\)$");
	private static final int NOT_IN_NAME = -1;

	private static final Map<Integer, Integer> CHARGE_VARBITS = Map.of(
		ItemID.VEOS_KHAREDSTS_MEMOIRS, VarbitID.VEOS_MEMOIR_CHARGES,
		ItemID.BOOK_OF_THE_DEAD, VarbitID.VEOS_MEMOIR_CHARGES);
	/** Profiles may still hold values that M1 read from varbits that are not counts; they are ignored and dropped. */
	private static final Set<Integer> UNTRUSTED_REMEMBERED = Set.of(
		ItemID.XERIC_TALISMAN, ItemID.RING_OF_SHADOWS, ItemID.CAMULET);
	private static final Map<Integer, String> ITEM_CHARGES_KEYS = Map.of(ItemID.CHRONICLE, "chronicle");

	private final Client client;
	private final ConfigManager configManager;
	private final MemorySlot<RememberedCharges> remembered;
	private final Map<Integer, Integer> chargesInName = new HashMap<>();

	@Inject
	ChargeReader(Client client, ConfigManager configManager, MemoryStore memory)
	{
		this.client = client;
		this.configManager = configManager;
		this.remembered = memory.slot(MEMORY_KEY, RememberedCharges.class, RememberedCharges::new);
	}

	/** The varbits whose change should rebuild the snapshot. */
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
			return new Charge(inName, Charge.Source.ITEM_ID);
		}
		Integer varbit = CHARGE_VARBITS.get(id);
		if (varbit != null)
		{
			int value = client.getVarbitValue(varbit);
			remember(id, value);
			return new Charge(value, Charge.Source.VARBIT);
		}
		Integer seen = seen().get(id);
		if (seen != null)
		{
			return new Charge(seen, Charge.Source.REMEMBERED);
		}
		String key = ITEM_CHARGES_KEYS.get(id);
		Integer fromPlugin = key == null ? null : configManager.getRSProfileConfiguration(ITEM_CHARGES_GROUP, key, Integer.class);
		return fromPlugin == null ? null : new Charge(fromPlugin, Charge.Source.ITEM_CHARGES_PLUGIN);
	}

	private int chargesInName(int id)
	{
		Matcher m = CHARGES_IN_NAME.matcher(client.getItemDefinition(id).getName());
		return m.find() ? Integer.parseInt(m.group(1)) : NOT_IN_NAME;
	}

	private void remember(int id, int value)
	{
		Map<Integer, Integer> current = seen();
		if (!Integer.valueOf(value).equals(current.get(id)))
		{
			RememberedCharges updated = new RememberedCharges();
			updated.charges.putAll(current);
			updated.charges.put(id, value);
			remembered.set(updated);
		}
	}

	private Map<Integer, Integer> seen()
	{
		Map<Integer, Integer> charges = remembered.get().charges;
		if (charges == null)
		{
			return Map.of();
		}
		Map<Integer, Integer> trusted = new HashMap<>(charges);
		trusted.keySet().removeAll(UNTRUSTED_REMEMBERED);
		return trusted;
	}

	/** The stored form: item id → the last charge count seen while held. */
	static final class RememberedCharges
	{
		Map<Integer, Integer> charges = new HashMap<>();
	}
}
