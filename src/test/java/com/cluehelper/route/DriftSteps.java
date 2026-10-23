package com.cluehelper.route;

import com.cluehelper.player.Book;
import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.Stage;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepIndex;
import com.cluehelper.step.TargetKind;
import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;

public final class DriftSteps
{
	private DriftSteps()
	{
	}

	/** Keeps the first var branch of each key. */
	public static Map<String, Step> read() throws IOException
	{
		StepIndex index = StepIndex.read(new Gson());
		Map<String, Step> read = new LinkedHashMap<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			DriftSteps.class.getResourceAsStream("/drift/core-steps.tsv"), StandardCharsets.UTF_8)))
		{
			in.readLine();
			String line;
			while ((line = in.readLine()) != null)
			{
				String[] c = line.split("\t", -1);
				if (read.containsKey(c[0]) || c[5].isEmpty())
				{
					continue;
				}
				List<WorldPoint> targets = Arrays.stream(c[5].split("\\|")).map(t ->
				{
					String[] p = t.split(",");
					return new WorldPoint(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
				}).collect(Collectors.toList());
				List<Integer> regions = c[7].isEmpty() ? List.of()
					: Arrays.stream(c[7].split("\\|")).map(Integer::parseInt).collect(Collectors.toList());
				TargetKind kind = c[4].isEmpty() ? TargetKind.ANY_OF : TargetKind.valueOf(c[4]);
				read.put(c[0], Step.builder().key(c[0]).type(c[2]).tier(c[3]).kind(kind).targets(targets).regions(regions)
					.stages(challengeStages(c[2], kind, targets, index.entry(c[0]))).text(c[12]).known(true).build());
			}
		}
		return read;
	}

	/** A skill challenge's targets are its task's places, any one of them, then the hand-in, as the resolver builds it. */
	private static List<Stage> challengeStages(String type, TargetKind kind, List<WorldPoint> targets, StepIndex.Entry entry)
	{
		if (!ClueTypes.SKILL.equals(type) || kind != TargetKind.SEQUENCE || targets.size() < 2)
		{
			return List.of();
		}
		List<WorldPoint> places = targets.subList(0, targets.size() - 1);
		Map<WorldPoint, String> grownOnly = entry == null ? Map.of() : entry.getPlaces().stream().filter(place -> place.getGrown() != null)
			.collect(Collectors.toMap(StepIndex.Place::getTile, StepIndex.Place::getGrown));
		Book book = entry == null || entry.getBook() == null ? null : Book.valueOf(entry.getBook());
		return List.of(Stage.builder().kind(Stage.Kind.TASK).tiles(List.copyOf(places)).grownOnly(grownOnly).takesStepItems(true)
				.book(book).build(),
			Stage.builder().kind(Stage.Kind.FINAL).tiles(List.of(targets.get(targets.size() - 1))).build());
	}
}
