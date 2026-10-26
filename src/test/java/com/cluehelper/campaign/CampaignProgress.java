package com.cluehelper.campaign;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Where the campaign stands, kept in tools/campaign/progress.json so it survives restarts. Claude edits it after a fix. */
final class CampaignProgress
{
	enum Status
	{
		UNTESTED,
		FINISHED,
		/** Waiting for a fix: out of the queue until it is set to RETEST. */
		BUG,
		/** Fixed, or its finished outcome changed: comes before every untested step. */
		RETEST
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Every step key, by tier and in a random order within each. */
	List<String> order = new ArrayList<>();
	String current;
	/** The untested step the walk through the order reached last: after the retests, the untested go on from there. */
	String untestedFrom;
	/** Steps not in it are untested. */
	Map<String, Status> statuses = new HashMap<>();
	Map<String, String> notes = new HashMap<>();
	/** The steps shown before the current one, the last most recent: Previous goes back through them. */
	List<String> history = new ArrayList<>();
	/** The golden test report last read, by modification time, so each report marks its steps once. */
	long changedSeen;

	Status status(String key)
	{
		return statuses.getOrDefault(key, Status.UNTESTED);
	}

	static CampaignProgress read(Path file) throws IOException
	{
		if (!Files.exists(file))
		{
			return new CampaignProgress();
		}
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			return GSON.fromJson(in, CampaignProgress.class);
		}
	}

	void write(Path file) throws IOException
	{
		Files.createDirectories(file.getParent());
		try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8))
		{
			GSON.toJson(this, out);
		}
	}

	Map<Status, Integer> counts()
	{
		Map<Status, Integer> counts = new HashMap<>();
		order.forEach(key -> counts.merge(status(key), 1, Integer::sum));
		return counts;
	}
}
