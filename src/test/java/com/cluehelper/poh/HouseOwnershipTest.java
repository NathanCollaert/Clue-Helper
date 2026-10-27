package com.cluehelper.poh;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** A wrong OWNED would silently teach the planner a stranger's furniture. */
public class HouseOwnershipTest
{
	@Test
	public void recordedEntriesGetTheirRecordedOwnership() throws IOException
	{
		long[] now = {0};
		HouseOwnership machine = null;
		String run = null;
		List<String> expected = new ArrayList<>();
		List<String> actual = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			HouseOwnershipTest.class.getResourceAsStream("/poh/entries.jsonl"), StandardCharsets.UTF_8)))
		{
			for (String line = in.readLine(); line != null; line = in.readLine())
			{
				JsonObject event = new JsonParser().parse(line).getAsJsonObject();
				if (!event.get("run").getAsString().equals(run))
				{
					run = event.get("run").getAsString();
					machine = new HouseOwnership(() -> now[0]);
				}
				now[0] = event.get("t").getAsLong();
				switch (event.get("type").getAsString())
				{
					case "click":
						machine.observeClick(event.get("option").getAsString(), event.get("target").getAsString());
						break;
					case "visit-start":
						machine.entered(event.get("buildMode").getAsBoolean());
						expected.add(event.get("description").getAsString() + " -> " + event.get("ownership").getAsString());
						actual.add(event.get("description").getAsString() + " -> " + machine.getOwnership());
						break;
					case "visit-end":
						machine.left();
						break;
					default:
						break;
				}
			}
		}
		assertEquals(8, expected.size());
		assertEquals(expected, actual);
	}

	@Test
	public void withdrawingAHouseTabletIsNoEntry()
	{
		long[] now = {0};
		HouseOwnership machine = new HouseOwnership(() -> now[0]);
		machine.observeClick("Withdraw-All", "<col=ff9040>Teleport to house</col>");
		now[0] = 3_000;
		machine.entered(false);
		assertEquals(HouseOwnership.Ownership.UNCERTAIN, machine.getOwnership());
	}
}
