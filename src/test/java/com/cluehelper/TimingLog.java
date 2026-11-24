package com.cluehelper;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.LongStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * The timing traces' files, in RuneLite's folder so they outlive {@code gradlew clean} and worktrees: each trace appends
 * one JSON line per measurement and rewrites its summary from all of them, off the client thread.
 */
@Slf4j
final class TimingLog
{
	static final File DIR = new File(RuneLite.RUNELITE_DIR, "clue-helper/timings");

	private final ExecutorService files = Executors.newSingleThreadExecutor(runnable ->
	{
		Thread thread = new Thread(runnable, "clue-helper-dev-timings");
		thread.setDaemon(true);
		return thread;
	});

	void append(File logFile, String line, File summary, Function<List<String>, String> summarise)
	{
		files.execute(() -> appendAndSummarise(logFile, line, summary, summarise));
	}

	void stop()
	{
		files.shutdown();
	}

	private static void appendAndSummarise(File logFile, String line, File summary, Function<List<String>, String> summarise)
	{
		try
		{
			Files.createDirectories(DIR.toPath());
			Files.write(logFile.toPath(), List.of(line), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			List<String> all = Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
			try (Writer out = Files.newBufferedWriter(summary.toPath(), StandardCharsets.UTF_8))
			{
				out.write(summarise.apply(all));
			}
		}
		catch (IOException e)
		{
			log.warn("could not write {} or {}", logFile.getAbsolutePath(), summary.getAbsolutePath(), e);
		}
	}

	/** "4", "4-6", or empty when there are no values. */
	static String range(LongStream values)
	{
		long[] sorted = values.sorted().toArray();
		if (sorted.length == 0)
		{
			return "";
		}
		long min = sorted[0];
		long max = sorted[sorted.length - 1];
		return min == max ? String.valueOf(min) : min + "-" + max;
	}
}
