package com.cluehelper.dev;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/** B04: prints the parsed trials of one events.jsonl: `gradlew b04Timings -Plog=<relative path>`. */
public class SpellbookTimingDump
{
	public static void main(String[] args) throws Exception
	{
		Gson gson = new Gson();
		List<JsonObject> events = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8).stream()
			.map(line -> gson.fromJson(line, JsonObject.class))
			.collect(Collectors.toList());
		for (SpellbookTimingParser.Trial trial : SpellbookTimingParser.parse(events))
		{
			System.out.println(trial);
		}
	}
}
