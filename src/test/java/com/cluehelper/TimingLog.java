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

	/** A second summary of the same log, rewritten after the lines appended before this call. */
	void summarise(File logFile, File summary, Function<List<String>, String> summarise)
	{
		files.execute(() -> writeSummary(logFile, summary, summarise));
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
		}
		catch (IOException e)
		{
			log.warn("could not write {}", logFile.getAbsolutePath(), e);
			return;
		}
		writeSummary(logFile, summary, summarise);
	}

	private static void writeSummary(File logFile, File summary, Function<List<String>, String> summarise)
	{
		try
		{
			List<String> all = Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
			try (Writer out = Files.newBufferedWriter(summary.toPath(), StandardCharsets.UTF_8))
			{
				out.write(summarise.apply(all));
			}
		}
		catch (IOException e)
		{
			log.warn("could not summarise {} into {}", logFile.getAbsolutePath(), summary.getAbsolutePath(), e);
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

	/** The middle value (the upper one of an even count), or empty when there are no values. */
	static String median(LongStream values)
	{
		long[] sorted = values.sorted().toArray();
		return sorted.length == 0 ? "" : String.valueOf(sorted[sorted.length / 2]);
	}

	/**
	 * Whether a measurement disagrees with what the plugin uses, judged only on a batch: below {@link Batch#ENOUGH}
	 * samples it says how many more are needed, so a few odd samples never pass for a finding. Then the median against
	 * the planned value: "fits", "slower by 2" or "faster by 1".
	 */
	static String verdict(LongStream measured, long planned)
	{
		long[] sorted = measured.sorted().toArray();
		if (sorted.length < Batch.ENOUGH)
		{
			return Batch.needs(sorted.length);
		}
		long median = sorted[sorted.length / 2];
		if (median == planned)
		{
			return "fits";
		}
		return (median > planned ? "slower by " : "faster by ") + Math.abs(median - planned);
	}

	/** How many samples a timing needs before it may be doubted. */
	static final class Batch
	{
		static final int ENOUGH = 10;

		private Batch()
		{
		}

		static String needs(int samples)
		{
			return "needs " + (ENOUGH - samples) + " more";
		}
	}
}
