package com.cluehelper.memory;

import java.util.HashMap;
import java.util.Map;

/**
 * Items that left the inventory or equipment right after a Deposit click, held until the bank's own update confirms
 * them. Walking off on the next tick closes the bank before that update arrives, so ticks without one mean the items
 * went in anyway.
 */
final class LateDeposits
{
	/** The server carries out a click on the next tick, and the walk-off can close the bank on that same tick. */
	static final int DEPOSIT_TICKS = 2;
	/**
	 * The bank's update comes in the same server cycle as the inventory's, before the next game tick; BankMemory runs
	 * before the planner on that tick, so the plan never sees the item missing.
	 */
	private static final int CONFIRM_TICKS = 1;
	private static final int NEVER = Integer.MIN_VALUE;

	private Map<Integer, Integer> carried = Map.of();
	private final Map<Integer, Integer> pending = new HashMap<>();
	private int clickTick = NEVER;
	private int bankSeenTick = NEVER;
	private int ticksWaited;

	void onDepositClicked(int tick)
	{
		clickTick = tick;
	}

	/** @param now the inventory and equipment together, unnoted, after any change to either */
	void onCarried(Map<Integer, Integer> now, int tick)
	{
		boolean bankAlreadySeen = tick == bankSeenTick;
		if (clickTick != NEVER && tick - clickTick <= DEPOSIT_TICKS && !bankAlreadySeen)
		{
			carried.forEach((id, before) -> {
				int gone = before - now.getOrDefault(id, 0);
				if (gone > 0)
				{
					pending.merge(id, gone, Integer::sum);
				}
			});
		}
		carried = Map.copyOf(now);
	}

	/** The bank's own update holds whatever was deposited this tick, whichever of the two updates came first. */
	void onBankSeen(int tick)
	{
		bankSeenTick = tick;
		pending.clear();
		ticksWaited = 0;
	}

	/** Each game tick: the deposits no bank update confirmed in time, returned once; empty otherwise. */
	Map<Integer, Integer> onTick()
	{
		if (pending.isEmpty() || ++ticksWaited < CONFIRM_TICKS)
		{
			return Map.of();
		}
		Map<Integer, Integer> unconfirmed = Map.copyOf(pending);
		pending.clear();
		ticksWaited = 0;
		return unconfirmed;
	}
}
