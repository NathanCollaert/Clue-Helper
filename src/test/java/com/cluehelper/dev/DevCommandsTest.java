package com.cluehelper.dev;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class DevCommandsTest
{
	private final List<String> replies = new ArrayList<>();
	private final DevCommands commands = new DevCommands(replies::add);

	@Test
	public void routesOurCommandsWithTheirArguments()
	{
		List<String[]> calls = new ArrayList<>();
		commands.register("steplog", "[clear]", calls::add);

		assertTrue(commands.dispatch("CH-StepLog", new String[]{"clear"}));

		assertEquals(1, calls.size());
		assertArrayEquals(new String[]{"clear"}, calls.get(0));
	}

	@Test
	public void leavesOtherPluginsCommandsAlone()
	{
		assertFalse(commands.dispatch("clue", new String[]{"some text"}));
		assertTrue(replies.isEmpty());
	}

	@Test
	public void unknownCommandsOfOursPointAtHelp()
	{
		assertTrue(commands.dispatch("ch-nope", new String[0]));
		assertEquals(List.of("Unknown command ::ch-nope, try ::ch-help"), replies);
	}

	@Test
	public void helpListsEveryCommand()
	{
		commands.register("selftest", "run the self-tests", args -> { });
		commands.dispatch("ch-help", new String[0]);
		assertEquals(List.of("::ch-help list these commands", "::ch-selftest run the self-tests"), replies);
	}
}
