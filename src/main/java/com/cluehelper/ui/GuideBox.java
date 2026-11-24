package com.cluehelper.ui;

import com.cluehelper.guide.SubStep;
import com.cluehelper.hotcold.HotColdView;
import com.cluehelper.player.Book;
import com.cluehelper.route.*;
import java.util.*;
import lombok.*;
import static java.util.stream.Collectors.*;

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
	/** Above the rest, in the highlight colour: the three-step held is the one to keep. */
	String headline;
	String now;
	@Builder.Default
	List<String> nowItems = List.of();
	String nowDetail;
	String next;
	@Builder.Default
	List<String> notes = List.of();
	String danger;
	/** Null outside a hot/cold step, or when the route says it all. */
	HotColdView hotCold;

	/** What the player is asked to do once, so that what they own counts. */
	@Value
	public static class Prompts
	{
		boolean openBank;
		/** The house is known only from the nexus varbits. */
		boolean visitHouse;
	}

	public boolean isShown()
	{
		return state.showsRoutes();
	}

	/** "Working…" only while there is nothing else to show: a re-plan keeps the last guidance on screen. */
	public String title()
	{
		return state == BoxState.WORKING && hasGuidance() ? null : state.getTitle();
	}

	public String detail()
	{
		return title() == null ? null : state.getDetail();
	}

	private boolean hasGuidance()
	{
		return now != null || hotCold != null || !notes.isEmpty() || danger != null;
	}

	/** At the step itself nothing is travelled: no notes about routes. */
	public GuideBox atTheStep()
	{
		List<String> kept = notes.stream().filter(note -> !ROUTE_NOTES.contains(note)).collect(toList());
		return toBuilder().notes(List.copyOf(kept)).build();
	}

	/**
	 * @param committed the route the guide follows, which the highlights show
	 * @param next null unless the next sub-step is wanted
	 * @param f2pBook the book a member is stuck on while on an F2P world, or null
	 */
	public static GuideBox of(BoxState state, Plan plan, Route committed, SubStep now, SubStep next, Book f2pBook, Prompts prompts)
	{
		GuideBoxBuilder box = GuideBox.builder().state(state);
		if (!state.showsRoutes() || plan == null)
		{
			return box.build();
		}
		if (plan.getDanger() != null)
		{
			return box.danger(plan.getDanger()).build();
		}
		if (now != null)
		{
			box.now(now.getText()).nowItems(now.getItems()).nowDetail(now.getDetail())
				.next(next == null ? null : upcomingLine(next));
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
		boolean planned = state != BoxState.PUT_BACK && !(plan == Plan.NONE && state == BoxState.WORKING);
		if (noRoute && notes.isEmpty() && planned)
		{
			notes.add(plan == Plan.NONE ? NO_PLAN_YET : NO_ROUTE);
		}
		return box.notes(notes).build();
	}

	/** The next line has no item lines under it, so a line that introduces its items ("Withdraw from the bank:") lists them inline. */
	private static String upcomingLine(SubStep next)
	{
		String text = next.getText();
		if (!text.endsWith(":"))
		{
			return text;
		}
		return next.getItems().isEmpty() ? text.substring(0, text.length() - 1) : text + " " + String.join(", ", next.getItems());
	}

	static String bookName(Book book)
	{
		return capitalise(book.name().toLowerCase(Locale.ROOT));
	}

	private static String capitalise(String text)
	{
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}
}
