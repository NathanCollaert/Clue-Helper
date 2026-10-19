package com.cluehelper.route;

import com.cluehelper.step.Step;
import com.cluehelper.step.TargetKind;
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

/** Steps as StepResolver gives them, rebuilt from the drift snapshot: for golden routes and the dev sampler. */
public final class DriftSteps
{
	private DriftSteps()
	{
	}

	/** The drift snapshot's resolved steps (one line per key, first var branch), as StepResolver gives them. */
	public static Map<String, Step> read() throws IOException
	{
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
					.text(c[12]).known(true).build());
			}
		}
		return read;
	}
}
