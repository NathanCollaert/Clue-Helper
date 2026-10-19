package com.cluehelper.dev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.cluehelper.memory.FakeProfileConfig;
import com.cluehelper.memory.MemoryStore;
import java.util.List;
import org.junit.Test;

public class StepLoggerTest
{
	private static final int MAX_ENTRIES = 10;

	private final FakeProfileConfig profile = new FakeProfileConfig();
	private final MemoryStore store = profile.newStore();
	private final StepLogger logger = new StepLogger(store, () -> 1_700_000_000L, MAX_ENTRIES);

	@Test
	public void recordsNothingOutsideDeveloperMode()
	{
		profile.logIn("main");
		logger.record("easy", "cryptic", "cryptic:a");
		assertTrue(logger.entries().isEmpty());
	}

	@Test
	public void appendsPerProfileAndSurvivesAReload()
	{
		logger.setEnabled(true);
		profile.logIn("main");
		logger.record("easy", "cryptic", "cryptic:a");
		logger.record("hard", "coordinate", "coordinate:2801");
		profile.logIn("alt");
		logger.record("elite", "emote", "emote:x");

		profile.logIn("main");
		store.reload();
		List<StepLog.Entry> main = logger.entries();
		assertEquals(2, main.size());
		assertEquals("hard coordinate coordinate:2801", main.get(1).toString());
		assertEquals(1_700_000_000L, main.get(1).getEpochSecond());

		profile.logIn("alt");
		assertEquals(1, logger.entries().size());
	}

	@Test
	public void keepsOnlyTheNewestEntries()
	{
		logger.setEnabled(true);
		profile.logIn("main");
		for (int i = 0; i < MAX_ENTRIES + 3; i++)
		{
			logger.record("easy", "cryptic", "cryptic:" + i);
		}
		List<StepLog.Entry> entries = logger.entries();
		assertEquals(MAX_ENTRIES, entries.size());
		assertEquals("cryptic:3", entries.get(0).getKey());
	}
}
