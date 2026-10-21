package com.cluehelper.ui;

import com.cluehelper.guide.SubStep;
import com.cluehelper.hotcold.HotColdView;
import com.cluehelper.player.Book;
import com.cluehelper.route.Plan;
import com.cluehelper.route.Route;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Value;

/** Built on the client thread and handed to the overlay as one immutable value. */
@Value
@Builder(toBuilder = true)
public class GuideBox
{
	public static final GuideBox EMPTY = GuideBox.builder().state(BoxState.NO_CLUE).build();
	private static final String NO_ROUTE = "No route from what you have";
	private static final String NO_PLAN_YET = "Waiting for the first plan";
	private static final Set<String> ROUTE_NOTES = Set.of(NO_ROUTE, NO_PLAN_YET, "Walk too long", "No target tile for this step yet");

	BoxState state;
	boolean carryingClue;
	/** Replaces the state's own detail line. */
	String statusDetail;
	String now;
	@Builder.Default
	List<String> nowItems = List.of();
	String nowDetail;
	String next;
	@Builder.Default
	List<String> notes = List.of();
	/** Null outside a hot/cold step, or with the probe planner off. */
	HotColdView hotCold;

	/** What the player is asked to do once, so that what they own counts. */
	@Value
	public static class Prompts
	{
		public static final Prompts NONE = new Prompts(false, false);

		boolean openBank;
		/** The house is known only from the nexus varbits. */
		boolean visitHouse;
	}

	public boolean isShown()
	{
		return state.shownWhen(carryingClue);
	}

	/** "Working…" only while there is nothing else to show: a re-plan keeps the last guidance on screen. */
	public String title()
	{
		return state == BoxState.WORKING && hasGuidance() ? null : state.getTitle();
	}

	public String detail()
	{
		return title() == null ? null : statusDetail != null ? statusDetail : state.getDetail();
	}

	private boolean hasGuidance()
	{
		return now != null || hotCold != null || !notes.isEmpty();
	}

	/** At the step itself nothing is travelled: no notes about routes. */
	public GuideBox atTheStep()
	{
		List<String> kept = notes.stream().filter(note -> !ROUTE_NOTES.contains(note)).collect(Collectors.toList());
		return toBuilder().notes(List.copyOf(kept)).build();
	}

	/**
	 * @param committed the route the guide follows, which the arrows and highlights show
	 * @param next null unless the next sub-step is wanted
	 * @param f2pBook the book a member is stuck on while on an F2P world, or null
	 * @param menuHint replaces the sub-step's hint
	 */
	public static GuideBox of(BoxState state, String dormantReason, Plan plan, Route committed, SubStep now, SubStep next,
		Book f2pBook, Prompts prompts, String menuHint)
	{
		GuideBoxBuilder box = GuideBox.builder().state(state);
		if (state == BoxState.DORMANT)
		{
			return box.statusDetail("Clue Helper stays off on this " + dormantReason + ".").build();
		}
		if (!state.showsRoutes() || plan == null)
		{
			return box.build();
		}
		if (now != null)
		{
			String detail = menuHint != null ? menuHint : now.getDetail();
			box.now(now.getText()).nowItems(now.getItems()).nowDetail(detail).next(next == null ? null : next.getText());
		}
		List<String> notes = new ArrayList<>();
		if (plan.getNote() != null && !plan.getNote().equals(Plan.NONE.getNote()))
		{
			notes.add(capitalise(plan.getNote()));
		}
		if (f2pBook != null)
		{
			notes.add("You're on " + bookName(f2pBook) + "; F2P worlds can't change book");
		}
		notes.addAll(plan.getRequirementNotes());
		if (prompts.isOpenBank())
		{
			notes.add("Open your bank once so banked teleports count");
		}
		if (prompts.isVisitHouse())
		{
			notes.add("Visit your house once so its furniture counts");
		}
		boolean noRoute = committed == null && plan.getBest() == null;
		if (noRoute && notes.isEmpty() && !(plan == Plan.NONE && state == BoxState.WORKING))
		{
			notes.add(plan == Plan.NONE ? NO_PLAN_YET : NO_ROUTE);
		}
		return box.notes(notes).build();
	}

	static String bookName(Book book)
	{
		String name = book.name().toLowerCase(Locale.ROOT);
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	private static String capitalise(String text)
	{
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}
}
