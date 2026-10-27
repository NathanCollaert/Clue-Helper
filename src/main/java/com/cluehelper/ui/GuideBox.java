package com.cluehelper.ui;

import com.cluehelper.guide.SubStep;
import com.cluehelper.hotcold.HotColdView;
import com.cluehelper.player.Book;
import com.cluehelper.route.*;
import java.awt.Color;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import lombok.*;
import net.runelite.client.util.ColorUtil;

/** Built on the client thread and handed to the overlay as one immutable value. */
@Value
@Builder(toBuilder = true)
public class GuideBox
{
	public static final GuideBox EMPTY = GuideBox.builder().state(BoxState.NO_CLUE).build();
	private static final String NO_ROUTE = "No route from what you have";
	private static final String NO_PLAN_YET = "Waiting for the first plan";
	/** The withdraw step's item lines' grey: what to click, apart from the white item it is clicked on. */
	private static final Color DESTINATION = new Color(190, 190, 190);
	/** A hotkey the guide coloured, with the ". " after it: "<col=40d0ff>M</col>. Falador Park". */
	private static final Pattern HOTKEY_LABEL = Pattern.compile("(<col=[0-9a-f]{6}>[^<]+</col>)\\. ");
	private static final String FAIRY_RING = "Fairy ring";
	private static final String HOTKEY_DASH = " - ";
	private static final String CAST = "Cast";
	private static final String BREAK = "Break";
	private static final String READ = "Read";
	private static final String USE = "Use";
	private static final String TABLET = " tablet";
	private static final String TAB = " tab";
	private static final String TELEPORT_TO = "Teleport to ";
	/** "Break the Varrock tablet", "Read the Watson teleport scroll", "Use the ectophial". */
	private static final Pattern VERB_THE = Pattern.compile("^(\\w+) the (.+)$");
	private static final Set<String> ROUTE_NOTES = Set.of(NO_ROUTE, NO_PLAN_YET, "Walk too long", "No target tile for this step yet");

	BoxState state;
	String now;
	@Builder.Default
	List<String> nowItems = List.of();
	String nowDetail;
	String next;
	@Builder.Default
	List<String> notes = List.of();
	/** Null outside a hot/cold step, with the probe planner off, or when the route says it all. */
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
		return now != null || hotCold != null || !notes.isEmpty();
	}

	/** At the step itself nothing is travelled: no notes about routes. */
	public GuideBox atTheStep()
	{
		List<String> kept = notes.stream().filter(note -> !ROUTE_NOTES.contains(note)).collect(Collectors.toList());
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
		if (now != null)
		{
			box.now(travelText(now)).nowItems(now.getItems()).nowDetail(now.getDetail())
				.next(next == null ? null : travelText(next));
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

	/**
	 * Every teleport reads alike, ITEM: DESTINATION HOTKEY: the item or object in the text's white, what to click in it
	 * ("POH Portals: Yanille") in the item lines' grey, and its hotkey last in the menu's blue. A step with no item
	 * ("Cast Varrock Teleport") keeps its words, the place in grey.
	 */
	private static String travelText(SubStep step)
	{
		String text = step.getText();
		if (!step.isTravel())
		{
			return text;
		}
		String destination = step.getRow() != null ? step.getRow().destination() : "";
		if (step.getKind() == SubStep.Kind.FAIRY_RING && text.startsWith(FAIRY_RING + " "))
		{
			String code = text.substring(FAIRY_RING.length() + 1);
			boolean coded = code.startsWith("<col");
			String where = coded ? destination : code.replaceFirst("^to ", "");
			String place = where.isEmpty() ? "" : coloured(where);
			return FAIRY_RING + ": " + (coded ? withHotkey(place, code) : place);
		}
		int colon = text.indexOf(": ");
		if (colon < 0)
		{
			return actionText(text, destination);
		}
		String item = text.substring(0, colon).replaceFirst("^Use ", "");
		Matcher hotkey = HOTKEY_LABEL.matcher(text.substring(colon + 2));
		String click = hotkey.replaceFirst("");
		return item + ": " + (hotkey.reset().find() ? withHotkey(coloured(click), hotkey.group(1)) : coloured(click));
	}

	/**
	 * A teleport with no item, as VERB: THING: "Cast: Varrock", "Break: Varrock teleport tab", "Use: Mort'ton teleport
	 * scroll", "Enter: Lumbridge portal".
	 */
	private static String actionText(String text, String destination)
	{
		if (text.startsWith(CAST + " "))
		{
			return CAST + ": " + coloured(destination.isEmpty() ? text.substring(CAST.length() + 1) : destination);
		}
		Matcher action = VERB_THE.matcher(text);
		if (!action.matches())
		{
			return text;
		}
		String verb = action.group(1);
		String thing = action.group(2);
		if (BREAK.equals(verb))
		{
			return BREAK + ": " + coloured(tabName(thing));
		}
		return (READ.equals(verb) ? USE : verb) + ": " + coloured(capitalise(thing));
	}

	/** A tablet as its item is called: "Varrock tablet" is the Varrock teleport tab. */
	private static String tabName(String tablet)
	{
		if (!tablet.contains(TABLET))
		{
			return tablet + TAB;
		}
		return tablet.replace(TABLET, tablet.startsWith(TELEPORT_TO) ? TAB : " teleport" + TAB);
	}

	/** "Falador Park - M": the dash in the text's white. */
	private static String withHotkey(String place, String hotkey)
	{
		return place.isEmpty() ? hotkey : place + HOTKEY_DASH + hotkey;
	}

	private static String coloured(String destination)
	{
		return ColorUtil.wrapWithColorTag(destination, DESTINATION);
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
