package com.cluehelper.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class MemoryStoreTest
{
	public static final class Bank
	{
		List<Integer> items = new ArrayList<>();
		long seenAt;
	}

	private final FakeProfileConfig profile = new FakeProfileConfig();
	private final MemoryStore store = profile.newStore();
	private final MemorySlot<Bank> bank = store.slot("bank", Bank.class, Bank::new);

	@Test
	public void roundTripsAValueThroughTheProfileConfig()
	{
		profile.logIn("main");
		Bank saved = new Bank();
		saved.items.add(995);
		saved.seenAt = 42;
		bank.set(saved);

		store.reload();
		Bank loaded = bank.get();

		assertEquals(List.of(995), loaded.items);
		assertEquals(42, loaded.seenAt);
		assertTrue(profile.values("main").get("bank").contains("995"));
	}

	@Test
	public void beforeLoginNothingIsReadOrWritten()
	{
		assertFalse(store.hasProfile());
		Bank early = bank.get();
		early.items.add(1);
		bank.set(early);
		assertTrue(profile.values("main").isEmpty());

		profile.logIn("main");
		assertTrue("the pre-login value isn't the profile's", bank.get().items.isEmpty());
	}

	@Test
	public void eachProfileKeepsItsOwnValue()
	{
		profile.logIn("main");
		Bank main = new Bank();
		main.seenAt = 1;
		bank.set(main);

		profile.logIn("ironman");
		store.reload();
		assertEquals(0, bank.get().seenAt);

		profile.logIn("main");
		assertEquals("a profile switch without the event still reloads", 1, bank.get().seenAt);
	}

	@Test
	public void unreadableJsonStartsFresh()
	{
		profile.logIn("main");
		profile.values("main").put("bank", "{not json");
		assertTrue(bank.get().items.isEmpty());
	}

	@Test
	public void clearRemovesTheStoredValue()
	{
		profile.logIn("main");
		MemorySlot<String> note = store.slot("note", String.class, () -> null);
		note.set("x");
		note.clear();
		store.reload();
		assertNull(note.get());
		assertFalse(profile.values("main").containsKey("note"));
	}
}
