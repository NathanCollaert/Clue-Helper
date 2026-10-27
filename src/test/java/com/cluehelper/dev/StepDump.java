package com.cluehelper.dev;

import com.cluehelper.link.ClueLink;
import com.cluehelper.link.StepChange;
import com.cluehelper.step.CoreClueReader;
import com.cluehelper.step.Step;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;

/**
 * Fixture lines use the replay fixtures' format, so a clue type the fixtures lack can be cut from the
 * log into src/test/resources.
 */
final class StepDump
{
	static final String TAG = "[CH-STEP]";
	private static final int SHOWN_TARGETS = 6;
	private static final int SHOWN_TEXT = 140;

	private final ClueLink link;
	private final Gson gson;

	StepDump(ClueLink link, Gson gson)
	{
		this.link = link;
		this.gson = gson;
	}

	/** Client thread only. */
	String fixtureLine(int tick, StepChange change, Step step)
	{
		JsonObject event = new JsonObject();
		event.addProperty("t", tick);
		event.addProperty("event", "clue");
		event.addProperty("change", change.name());
		ClueScroll clue = link.coreClue();
		if (step != null && clue != null && link.getCore() != null)
		{
			event.addProperty("class", clue.getClass().getSimpleName());
			JsonObject fields = gson.toJsonTree(CoreClueReader.facts(clue, link.getCore())).getAsJsonObject();
			JsonArray targets = new JsonArray();
			for (WorldPoint point : step.getTargets())
			{
				targets.add(gson.toJsonTree(new int[]{point.getX(), point.getY(), point.getPlane()}));
			}
			fields.add("targets", targets);
			fields.add("npcs", gson.toJsonTree(step.getNpcs()));
			event.add("fields", fields);
			event.addProperty("key", step.getKey());
		}
		return TAG + " fixture " + gson.toJson(event);
	}

	static List<String> lines(Step step)
	{
		List<String> lines = new ArrayList<>();
		lines.add("Step " + step.getKey());
		lines.add(step.getType() + ", " + step.getTier() + ", " + step.getKind()
			+ (step.isKnown() ? ", rows " + step.getCacheRows() : ", no step data"));
		lines.add("Targets (" + step.getTargets().size() + "): " + tiles(step.getTargets()));
		if (!step.getNpcs().isEmpty())
		{
			lines.add("NPCs: " + String.join(", ", step.getNpcs()) + (step.getRegions().isEmpty() ? "" : " in regions " + step.getRegions()));
		}
		if (!step.getObjectIds().isEmpty() || !step.getObjectNames().isEmpty())
		{
			lines.add("Objects: " + step.getObjectIds() + " " + step.getObjectNames());
		}
		lines.add("Needs: " + step.getRequirements());
		String text = step.getText() == null ? "" : step.getText();
		lines.add("Text: " + (text.length() > SHOWN_TEXT ? text.substring(0, SHOWN_TEXT) + "…" : text));
		return lines;
	}

	private static String tiles(List<WorldPoint> points)
	{
		String shown = points.stream().limit(SHOWN_TARGETS)
			.map(p -> p.getX() + "," + p.getY() + "," + p.getPlane())
			.collect(Collectors.joining("; "));
		return points.size() > SHOWN_TARGETS ? shown + "; +" + (points.size() - SHOWN_TARGETS) + " more" : shown;
	}
}
