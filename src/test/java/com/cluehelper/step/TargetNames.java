package com.cluehelper.step;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The cache names of the objects and NPCs the steps' places name, from {@code target-names.tsv} (generate.py). */
final class TargetNames
{
	static final Map<Integer, String> OBJECTS = new HashMap<>();
	static final Map<Integer, String> NPCS = new HashMap<>();

	static
	{
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			Objects.requireNonNull(TargetNames.class.getResourceAsStream("target-names.tsv"), "target-names.tsv is missing"),
			StandardCharsets.UTF_8)))
		{
			in.lines().filter(line -> !line.startsWith("#") && !line.startsWith("kind\t")).map(line -> line.split("\t", 3))
				.forEach(row -> ("object".equals(row[0]) ? OBJECTS : NPCS).put(Integer.parseInt(row[1]), row[2]));
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("reading target-names.tsv", e);
		}
	}

	private TargetNames()
	{
	}
}
