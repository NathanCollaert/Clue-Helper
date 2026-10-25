package com.cluehelper.campaign;

import com.cluehelper.route.Plan;
import com.cluehelper.route.PlanInput;
import com.cluehelper.route.SceneFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;

/**
 * The step campaign: every clue step, one mocked clue at a time. Finish saves the step's first plan as a golden
 * recording once it replays offline to what was shown; Bug saves a report for Claude; Previous and Next move without a
 * verdict. Steps waiting for a retest come first, then untested ones in order; reported bugs wait for their fix. No clue is
 * mocked until Next is pressed: the first Next of a session opens the saved step. Stop clears the clue and leaves the
 * step as it was, so the next Next opens it again.
 * Buttons may be pressed on any thread; the campaign's state lives on the client thread, its files on a worker.
 */
@Slf4j
public final class Campaign
{
	private static final String TAG = "[CH-CAMPAIGN]";
	private static final List<String> TIERS = List.of("beginner", "easy", "medium", "hard", "elite", "master");
	/** The Grand Exchange, where the walk through each tier starts. */
	private static final WorldPoint TOUR_START = new WorldPoint(3164, 3486, 0);
	private static final Path STAGING = Paths.get("build/campaign/staging");
	private static final String NOTE_FILE = "note.txt";
	private static final String GUIDE_FILE = "guide-now.txt";
	private static final String CHECK_FILE = "replay-check.txt";
	private static final String SCREENSHOT_FILE = "screenshot.png";

	/** Hands the Clue Scroll plugin a step's clue, or takes it away; client thread. */
	public interface Clues
	{
		void mock(CampaignStep step);

		void clear();
	}

	/** A step the campaign can mock. */
	@Value
	public static class CampaignStep
	{
		String key;
		String tier;
		String type;
		String text;
		/** Its place in core's lists, as {@link com.cluehelper.step.CoreClues#entry} takes it. */
		String coreClue;
		/** Null for a step without a tile. */
		WorldPoint target;
	}

	private final ClientThread clientThread;
	private final CampaignRecorder recorder;
	private final Supplier<List<CampaignStep>> steps;
	private final Clues clues;
	private final Supplier<List<String>> guideNow;
	private final BiConsumer<Path, Runnable> screenshot;
	private final Consumer<String> show;
	private final ExecutorService files;

	private CampaignProgress progress;
	private boolean started;
	private Map<String, CampaignStep> byKey = Map.of();
	private String armed;
	private CampaignRecorder.Capture capture;
	private String message = "";
	private CampaignReplay replay;

	/**
	 * @param steps every step core can hand out; client thread
	 * @param guideNow the guide's sub-steps as shown now; client thread
	 * @param screenshot saves the next game frame to the path, then runs the callback, also when no frame could be taken
	 * @param show the panel's text; any thread
	 * @param files saves and replays recordings off the client thread
	 */
	public Campaign(ClientThread clientThread, CampaignRecorder recorder, Supplier<List<CampaignStep>> steps, Clues clues,
		Supplier<List<String>> guideNow, BiConsumer<Path, Runnable> screenshot, Consumer<String> show, ExecutorService files)
	{
		this.clientThread = clientThread;
		this.recorder = recorder;
		this.steps = steps;
		this.clues = clues;
		this.guideNow = guideNow;
		this.screenshot = screenshot;
		this.show = show;
		this.files = files;
	}

	/** Client thread, once logged in: shows the saved step, without mocking its clue yet. */
	public void resume()
	{
		try
		{
			progress = CampaignProgress.read(CampaignFiles.PROGRESS);
		}
		catch (IOException e)
		{
			log.warn("{} can't read {}", TAG, CampaignFiles.PROGRESS, e);
			say("Can't read " + CampaignFiles.PROGRESS + ": see the log");
			return;
		}
		byKey = steps.get().stream().collect(Collectors.toMap(CampaignStep::getKey, step -> step, (a, b) -> a, LinkedHashMap::new));
		updateOrder();
		markFinishedFromGoldens();
		markChangedForRetest();
		if (progress.current == null || !byKey.containsKey(progress.current))
		{
			progress.current = next(null);
		}
		saveProgress();
		log.info("{} {} steps, at {}", TAG, progress.order.size(), progress.current);
		say("Press Next to start");
	}

	/** Client thread. The step's first plan is the one Finish saves. */
	public void onPlan(Plan plan, PlanInput input)
	{
		if (armed == null || input.getStep() == null || !armed.equals(input.getStep().getKey()))
		{
			return;
		}
		armed = null;
		try
		{
			capture = recorder.capture(byKey.get(input.getStep().getKey()).getCoreClue(), plan, input);
			capture.getProblems().forEach(problem -> log.warn("{} {}: {}", TAG, input.getStep().getKey(), problem));
			say(capture.getProblems().isEmpty() ? "Route captured" : "Route captured with problems: see the log");
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			log.warn("{} capture of {} failed", TAG, input.getStep().getKey(), e);
			say("Capture failed: see the log");
		}
	}

	public void finish()
	{
		clientThread.invokeLater(() ->
		{
			if (!startedOrSay())
			{
				return;
			}
			if (capture == null || !capture.getRecording().key.equals(progress.current))
			{
				say("No route captured for this step yet: wait for its first plan");
				return;
			}
			if (!capture.getProblems().isEmpty())
			{
				say("Not saved: " + capture.getProblems().get(0));
				return;
			}
			CampaignRecorder.Capture saving = capture;
			say("Checking the replay...");
			files.execute(() -> save(saving));
		});
	}

	public void bug(String note)
	{
		if (note == null || note.isBlank())
		{
			say("A bug needs a note");
			return;
		}
		clientThread.invokeLater(() ->
		{
			if (!startedOrSay())
			{
				return;
			}
			String key = progress.current;
			CampaignRecorder.Capture reported = capture != null && capture.getRecording().key.equals(key) ? capture : null;
			List<String> guide = guideNow.get();
			Path folder = CampaignFiles.BUGS.resolve(CampaignFiles.slug(key));
			progress.statuses.put(key, CampaignProgress.Status.BUG);
			progress.notes.put(key, note.trim());
			files.execute(() -> report(folder, key, note.trim(), guide, reported));
			screenshot.accept(folder.resolve(SCREENSHOT_FILE), () -> clientThread.invokeLater(this::advance));
		});
	}

	/** The session's first Next opens the saved step; every later one moves on. */
	public void next()
	{
		clientThread.invokeLater(() ->
		{
			if (progress == null)
			{
				return;
			}
			if (started)
			{
				advance();
				return;
			}
			started = true;
			if (progress.current == null)
			{
				advance();
				return;
			}
			mockCurrent();
		});
	}

	/** Ends the session: the clue goes, nothing is recorded, and the step stays where it was. */
	public void stop()
	{
		clientThread.invokeLater(() ->
		{
			if (!startedOrSay())
			{
				return;
			}
			started = false;
			armed = null;
			capture = null;
			clues.clear();
			say("Stopped: Next starts again at this step");
		});
	}

	public void previous()
	{
		clientThread.invokeLater(() ->
		{
			if (!startedOrSay())
			{
				return;
			}
			if (progress.history.isEmpty())
			{
				say("No step before this one");
				return;
			}
			progress.current = progress.history.remove(progress.history.size() - 1);
			saveProgress();
			mockCurrent();
		});
	}

	private boolean startedOrSay()
	{
		if (!started)
		{
			say("Press Next to start");
		}
		return started;
	}

	private void advance()
	{
		String next = next(progress.current);
		if (next == null)
		{
			say("Every step is finished or waiting for a fix");
			saveProgress();
			showState();
			return;
		}
		if (progress.current != null)
		{
			progress.history.add(progress.current);
		}
		if (progress.status(next) == CampaignProgress.Status.UNTESTED)
		{
			progress.untestedFrom = next;
		}
		progress.current = next;
		saveProgress();
		mockCurrent();
	}

	/**
	 * Steps to retest first, then the next untested one after the untested step reached last (else after {@code from}),
	 * wrapping round; never {@code from} itself. A retest further down the order doesn't move where the untested go on.
	 */
	private String next(String from)
	{
		List<String> order = progress.order;
		for (String key : order)
		{
			if (!key.equals(from) && progress.status(key) == CampaignProgress.Status.RETEST)
			{
				return key;
			}
		}
		String resume = progress.untestedFrom != null ? progress.untestedFrom : from;
		int at = resume == null ? -1 : order.indexOf(resume);
		for (int i = 1; i <= order.size(); i++)
		{
			String key = order.get(Math.floorMod(at + i, order.size()));
			if (!key.equals(from) && progress.status(key) == CampaignProgress.Status.UNTESTED)
			{
				return key;
			}
		}
		return null;
	}

	private void mockCurrent()
	{
		capture = null;
		CampaignStep step = byKey.get(progress.current);
		if (step == null)
		{
			showState();
			return;
		}
		armed = step.getKey();
		message = "";
		clues.mock(step);
		showState();
	}

	private void save(CampaignRecorder.Capture saving)
	{
		CampaignRecording recording = saving.getRecording();
		try
		{
			Outcome replayed = write(STAGING, saving);
			Outcome.Change change = recording.expected.changeTo(replayed);
			if (change != Outcome.Change.NONE)
			{
				log.warn("{} {} not saved, the replay differs ({}):\n  shown    {}\n  replayed {}", TAG, recording.key, change,
					recording.expected, replayed);
				say("Not saved: the replay differs (" + change + "), see the log");
				return;
			}
			Path golden = CampaignFiles.goldenFile(recording.tier, recording.key);
			Files.createDirectories(golden.getParent());
			Files.move(STAGING.resolve(golden.getFileName()), golden, StandardCopyOption.REPLACE_EXISTING);
			if (recording.sceneFile != null)
			{
				Files.move(STAGING.resolve(recording.sceneFile), golden.resolveSibling(recording.sceneFile), StandardCopyOption.REPLACE_EXISTING);
			}
			log.info("{} {} finished: {}", TAG, recording.key, golden);
			clientThread.invokeLater(() ->
			{
				progress.statuses.put(recording.key, CampaignProgress.Status.FINISHED);
				progress.notes.remove(recording.key);
				advance();
				say("Saved " + golden.getFileName());
			});
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("{} saving {} failed", TAG, recording.key, e);
			say("Saving failed: see the log");
		}
	}

	/** Writes the recording and its scene to {@code folder}, then replays them from there. Files thread. */
	private Outcome write(Path folder, CampaignRecorder.Capture saving) throws IOException
	{
		CampaignRecording recording = saving.getRecording();
		String slug = CampaignFiles.slug(recording.key);
		Files.createDirectories(folder);
		if (saving.getScene() != null)
		{
			recording.sceneFile = slug + CampaignFiles.SCENE_SUFFIX;
			SceneFiles.write(folder.resolve(recording.sceneFile).toFile(), saving.getScene(), saving.getSceneTile());
		}
		CampaignFiles.write(folder.resolve(slug + CampaignFiles.RECORDING_SUFFIX), recording);
		if (replay == null)
		{
			replay = CampaignReplay.load();
		}
		return replay.replay(recording, folder);
	}

	private void report(Path folder, String key, String note, List<String> guide, CampaignRecorder.Capture reported)
	{
		try
		{
			Files.createDirectories(folder);
			Files.write(folder.resolve(NOTE_FILE), List.of(key, "", note), StandardCharsets.UTF_8);
			Files.write(folder.resolve(GUIDE_FILE), guide, StandardCharsets.UTF_8);
			List<String> check = new ArrayList<>();
			if (reported == null)
			{
				check.add("No route was captured for this step.");
			}
			else
			{
				Outcome replayed = write(folder, reported);
				check.add("change: " + reported.getRecording().expected.changeTo(replayed));
				check.add("shown:    " + reported.getRecording().expected);
				check.add("replayed: " + replayed);
				reported.getProblems().forEach(problem -> check.add("problem: " + problem));
			}
			Files.write(folder.resolve(CHECK_FILE), check, StandardCharsets.UTF_8);
			log.info("{} bug reported for {}: {}", TAG, key, folder);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("{} the bug report for {} failed", TAG, key, e);
			say("Writing the bug report failed: see the log");
		}
	}

	/** New core steps join at the end; steps core no longer has drop out. */
	private void updateOrder()
	{
		Set<String> known = new HashSet<>(progress.order);
		progress.order.removeIf(key -> !byKey.containsKey(key));
		List<CampaignStep> added = byKey.values().stream().filter(step -> !known.contains(step.getKey())).collect(Collectors.toList());
		progress.order.addAll(tour(added));
	}

	/** By tier, and within a tier from each step to the nearest one left, so steps in a row are close. */
	private static List<String> tour(List<CampaignStep> steps)
	{
		List<String> order = new ArrayList<>();
		Map<String, List<CampaignStep>> byTier = steps.stream().collect(Collectors.groupingBy(CampaignStep::getTier));
		List<String> tiers = new ArrayList<>(byTier.keySet());
		tiers.sort(Comparator.comparingInt(tier -> TIERS.contains(tier) ? TIERS.indexOf(tier) : TIERS.size()));
		for (String tier : tiers)
		{
			List<CampaignStep> left = byTier.get(tier).stream().filter(step -> step.getTarget() != null).collect(Collectors.toList());
			WorldPoint at = TOUR_START;
			while (!left.isEmpty())
			{
				WorldPoint from = at;
				CampaignStep nearest = left.stream().min(Comparator.comparingInt(step -> distance(from, step.getTarget()))).orElseThrow();
				left.remove(nearest);
				order.add(nearest.getKey());
				at = nearest.getTarget();
			}
			byTier.get(tier).stream().filter(step -> step.getTarget() == null).forEach(step -> order.add(step.getKey()));
		}
		return order;
	}

	private static int distance(WorldPoint a, WorldPoint b)
	{
		return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
	}

	/** A golden recording on disk means the step was finished, even when progress.json was lost. */
	private void markFinishedFromGoldens()
	{
		for (String key : progress.order)
		{
			CampaignStep step = byKey.get(key);
			if (progress.status(key) == CampaignProgress.Status.UNTESTED && Files.exists(CampaignFiles.goldenFile(step.getTier(), key)))
			{
				progress.statuses.put(key, CampaignProgress.Status.FINISHED);
			}
		}
	}

	/** A finished step whose outcome the last golden test found changed beyond ticks is played again. */
	private void markChangedForRetest()
	{
		try
		{
			if (!Files.exists(CampaignFiles.CHANGED) || Files.getLastModifiedTime(CampaignFiles.CHANGED).toMillis() <= progress.changedSeen)
			{
				return;
			}
			progress.changedSeen = Files.getLastModifiedTime(CampaignFiles.CHANGED).toMillis();
			for (String line : Files.readAllLines(CampaignFiles.CHANGED, StandardCharsets.UTF_8))
			{
				String[] parts = line.split("\t");
				if (parts.length == 3 && parts[0].equals("route") && progress.status(parts[1]) == CampaignProgress.Status.FINISHED)
				{
					progress.statuses.put(parts[1], CampaignProgress.Status.RETEST);
					log.info("{} {} changed: retest", TAG, parts[1]);
				}
			}
		}
		catch (IOException e)
		{
			log.warn("{} can't read {}", TAG, CampaignFiles.CHANGED, e);
		}
	}

	private void saveProgress()
	{
		try
		{
			progress.write(CampaignFiles.PROGRESS);
		}
		catch (IOException e)
		{
			log.warn("{} can't save {}", TAG, CampaignFiles.PROGRESS, e);
			say("Can't save progress: see the log");
		}
	}

	/** Any thread. */
	private void say(String text)
	{
		log.info("{} {}", TAG, text);
		clientThread.invokeLater(() ->
		{
			message = text;
			showState();
		});
	}

	private void showState()
	{
		if (progress == null)
		{
			show.accept("Starts once logged in\n" + message);
			return;
		}
		String key = progress.current;
		CampaignStep step = key == null ? null : byKey.get(key);
		Map<CampaignProgress.Status, Integer> counts = progress.counts();
		String tally = progress.order.size() + " steps: " + Arrays.stream(CampaignProgress.Status.values())
			.map(status -> counts.getOrDefault(status, 0) + " " + status.name().toLowerCase())
			.collect(Collectors.joining(", "));
		String where = step == null ? "No step" : (progress.order.indexOf(key) + 1) + " / " + progress.order.size() + "  " + step.getTier()
			+ " " + step.getType() + "  " + progress.status(key).name().toLowerCase();
		String note = key == null ? null : progress.notes.get(key);
		show.accept(where + "\n" + (step == null ? "" : step.getKey() + "\n" + step.getText() + "\n") + (note == null ? "" : "Bug: " + note + "\n")
			+ tally + "\n" + message);
	}
}
