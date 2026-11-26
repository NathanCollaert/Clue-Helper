package com.cluehelper;

import static org.junit.Assert.assertEquals;

import com.cluehelper.route.PlanRecording;
import com.cluehelper.route.Planner;
import com.cluehelper.route.Route;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Plans recorded in the client, planned again: the inputs a hand-built case would miss. */
public class RecordedRoutesTest
{
	/**
	 * In the Brimhaven Agility Arena pen, which opens only to the south, the walk to the hot/cold dig spot and the Max cape
	 * to the house then its spirit tree tie on rank: the walk wins, with no teleports.
	 */
	@Test
	public void aTieWithTwoTeleportsWalks() throws IOException
	{
		PlanRecording recording = recording("/com/cluehelper/route/hotcold-from-the-agility-arena-pen.json");
		Route best = Planner.plan(new PlanReplay().input(recording, null)).getBest();
		assertEquals("walk 28", best.describe());
	}

	private static PlanRecording recording(String resource) throws IOException
	{
		try (InputStream in = RecordedRoutesTest.class.getResourceAsStream(resource))
		{
			return new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), PlanRecording.class);
		}
	}
}
