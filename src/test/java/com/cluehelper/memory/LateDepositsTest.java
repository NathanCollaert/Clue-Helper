package com.cluehelper.memory;

import static org.junit.Assert.assertEquals;

import java.util.Map;
import org.junit.Test;

/** B16: Nathan deposited his Max cape and walked off the next tick; the bank update never came. */
public class LateDepositsTest
{
	private static final int MAX_CAPE = 13280;
	private static final int COINS = 995;

	@Test
	public void depositWithoutBankUpdateCountsOnceAndOnlyAfterAClick()
	{
		assertEquals("walked off before the bank update", Map.of(MAX_CAPE, 1), run(true, false, false));
		assertEquals("bank update after the inventory's", Map.of(), run(true, true, false));
		assertEquals("bank update before the inventory's", Map.of(), run(true, false, true));
		assertEquals("no Deposit click: eaten, dropped or worn", Map.of(), run(false, false, false));
	}

	private static Map<Integer, Integer> run(boolean clicked, boolean bankAfter, boolean bankBefore)
	{
		LateDeposits deposits = new LateDeposits();
		deposits.onCarried(Map.of(MAX_CAPE, 1, COINS, 100), 10);
		if (clicked)
		{
			deposits.onDepositClicked(10);
		}
		if (bankBefore)
		{
			deposits.onBankSeen(11);
		}
		deposits.onCarried(Map.of(COINS, 100), 11);
		if (bankAfter)
		{
			deposits.onBankSeen(11);
		}
		Map<Integer, Integer> first = deposits.onTick();
		assertEquals("released twice", Map.of(), deposits.onTick());
		return first;
	}
}
