package com.cluehelper.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PanelStateTest
{
	@Test
	public void coreOffOverridesEverything()
	{
		assertEquals(PanelState.CORE_OFF, PanelState.of(false, true, true, true));
	}

	@Test
	public void anUnreadClueComesBeforeNoClue()
	{
		assertEquals(PanelState.READ_CLUE, PanelState.of(true, true, false, false));
		assertEquals(PanelState.NO_CLUE, PanelState.of(true, false, false, false));
	}

	@Test
	public void aStepShowsWorkingThenRoutes()
	{
		assertEquals(PanelState.WORKING, PanelState.of(true, false, true, true));
		assertEquals(PanelState.ROUTES, PanelState.of(true, false, true, false));
	}
}
