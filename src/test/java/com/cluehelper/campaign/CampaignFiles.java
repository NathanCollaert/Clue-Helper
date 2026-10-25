package com.cluehelper.campaign;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Where the campaign keeps its files, and how a recording is written: gzipped JSON, its scene beside it. */
public final class CampaignFiles
{
	/** The golden recordings, one folder per tier. They ship in the public repo. */
	public static final Path GOLDENS = Paths.get("src/test/resources/campaign");
	/** Progress and bug reports stay local: tools/ is never committed. */
	public static final Path LOCAL = Paths.get("tools/campaign");
	public static final Path BUGS = LOCAL.resolve("bugs");
	public static final Path PROGRESS = LOCAL.resolve("progress.json");
	/** The golden test's report: each changed key with {@code route} or {@code ticks}, and the page that shows why. */
	public static final Path CHANGED = Paths.get("build/campaign/changed.txt");
	public static final Path CHANGED_PAGE = Paths.get("build/campaign/changed.html");
	static final String RECORDING_SUFFIX = ".json.gz";
	static final String SCENE_SUFFIX = ".scene.gz";
	private static final int SLUG_CHARS = 48;

	private static final Gson GSON = new Gson();

	private CampaignFiles()
	{
	}

	/** Short and unique per key: long cryptic texts share their first words. */
	public static String slug(String key)
	{
		String words = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
		return words.substring(0, Math.min(SLUG_CHARS, words.length())) + "-" + Integer.toHexString(key.hashCode());
	}

	static Path goldenFile(String tier, String key)
	{
		return GOLDENS.resolve(tier).resolve(slug(key) + RECORDING_SUFFIX);
	}

	static List<Path> goldenFiles() throws IOException
	{
		try (Stream<Path> files = Files.walk(GOLDENS))
		{
			return files.filter(file -> file.getFileName().toString().endsWith(RECORDING_SUFFIX)).sorted().collect(Collectors.toList());
		}
	}

	static CampaignRecording read(Path file) throws IOException
	{
		try (Reader in = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))
		{
			return GSON.fromJson(in, CampaignRecording.class);
		}
	}

	static void write(Path file, CampaignRecording recording) throws IOException
	{
		Files.createDirectories(file.getParent());
		try (Writer out = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8))
		{
			GSON.toJson(recording, out);
		}
	}
}
