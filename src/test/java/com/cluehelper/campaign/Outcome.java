package com.cluehelper.campaign;

import com.cluehelper.guide.StepGuide;
import com.cluehelper.guide.SubStep;
import com.cluehelper.route.Plan;
import com.cluehelper.route.RouteText;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** What a step's first plan showed: the route as golden lines, the guide's sub-steps, and what each one outlines. */
@Value
public class Outcome
{
	/** The tick counts in a route line: {@code 20t}, {@code (rank 21)}, {@code walk 14}, {@code walk south 3}. */
	private static final Pattern TICKS = Pattern.compile("\\b\\d+t\\b|(?<=\\(rank )\\d+(?=\\))|(?<=walk )\\d+|(?<=walk south )\\d+");

	/** A route line's ranking note, which only says how rank and ticks differ, so its showing up is no route change. */
	private static final Pattern RANK_NOTE = Pattern.compile(" \\(rank \\d+\\)");

	List<String> route;
	List<String> guide;
	/** Per guide line, what it outlines and clicks; null in a recording made before these were kept. */
	List<String> targets;

	public enum Change
	{
		NONE,
		/** The same route and guide; only tick counts moved. */
		TICKS,
		/** The same route and guide lines; what a line outlines or clicks changed. */
		TARGETS,
		ROUTE
	}

	static Outcome of(Plan plan, List<SubStep> guide)
	{
		List<String> route = new ArrayList<>();
		route.add("best " + RouteText.describe(plan.getBest()));
		if (plan.getNote() != null)
		{
			route.add("note " + plan.getNote());
		}
		plan.getRequirementNotes().forEach(note -> route.add("needs " + note));
		return new Outcome(List.copyOf(route), guideLines(guide), targetLines(guide));
	}

	public static List<String> guideLines(List<SubStep> guide)
	{
		return guide.stream().map(step -> step.getKind() + ": " + step.getText() + (step.getDetail() == null ? "" : " (" + step.getDetail() + ")"))
			.collect(Collectors.toList());
	}

	/** Each sub-step's objects, NPCs, option, tab, tiles, items, way in and teleport row, as the overlays take them. */
	public static List<String> targetLines(List<SubStep> guide)
	{
		return guide.stream().map(Outcome::targets).collect(Collectors.toList());
	}

	private static String targets(SubStep step)
	{
		List<String> parts = new ArrayList<>();
		addIds(parts, "objects", step.getObjectIds());
		addIds(parts, "npcs", step.getNpcIds());
		addIds(parts, "npc names", step.getNpcNames());
		addText(parts, "option", step.getOption());
		addText(parts, "tab", step.getTab() == StepGuide.Tab.NONE ? null : step.getTab().name());
		addText(parts, "to", tile(step.getWaypoint()));
		addText(parts, "goal", step.getGoal() == null ? null : tile(step.getGoal()) + " r" + step.getRadius());
		addIds(parts, "act items", step.getActItems());
		addIds(parts, "bank items", step.getBankItems());
		addIds(parts, "held when done", step.getHeldWhenDone());
		addText(parts, "row", step.getRow() == null ? null : step.getRow().getName() + " " + step.getRow().getClicks());
		addText(parts, "way in", step.getWayIn() == null ? null : step.getWayIn().getObject() + " " + step.getWayIn().getOption());
		addText(parts, "stage", step.getStage() == null ? null : step.getStage().getKind() + " at " + step.getStage().getTiles().size());
		addText(parts, "track", step.getTrack());
		addText(parts, "done", step.getDone().name());
		return String.join("; ", parts);
	}

	private static void addIds(List<String> parts, String name, Collection<?> ids)
	{
		if (!ids.isEmpty())
		{
			parts.add(name + " " + ids.stream().map(String::valueOf).sorted().collect(Collectors.toList()));
		}
	}

	private static void addText(List<String> parts, String name, String value)
	{
		if (value != null)
		{
			parts.add(name + " " + value);
		}
	}

	private static String tile(WorldPoint tile)
	{
		return tile == null ? null : tile.getX() + "," + tile.getY() + "," + tile.getPlane();
	}

	/** How {@code now} differs from this, the signed-off outcome; targets count only once the recording has them. */
	Change changeTo(Outcome now)
	{
		boolean sameLines = guide.equals(now.guide) && withoutTicks(route).equals(withoutTicks(now.route));
		boolean sameTargets = targets == null || targets.equals(now.targets);
		if (sameLines && sameTargets)
		{
			return route.equals(now.route) ? Change.NONE : Change.TICKS;
		}
		return sameLines ? Change.TARGETS : Change.ROUTE;
	}

	/** A recording from before targets were kept, whose route and guide still replay as they were. */
	boolean lacksTargetsOf(Outcome now)
	{
		return targets == null && route.equals(now.route) && guide.equals(now.guide);
	}

	/** The same route, tick counts aside, whatever the guide's lines say now. */
	boolean isSameRouteAs(Outcome now)
	{
		return withoutTicks(route).equals(withoutTicks(now.route));
	}

	private static List<String> withoutTicks(List<String> lines)
	{
		return lines.stream().map(line -> TICKS.matcher(RANK_NOTE.matcher(line).replaceAll("")).replaceAll("#")).collect(Collectors.toList());
	}
}
