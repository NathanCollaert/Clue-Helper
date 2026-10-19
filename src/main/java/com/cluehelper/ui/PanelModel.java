package com.cluehelper.ui;

import com.cluehelper.guide.SubStep;
import com.cluehelper.player.Book;
import com.cluehelper.route.Leg;
import com.cluehelper.route.Plan;
import com.cluehelper.route.Route;
import com.cluehelper.transport.RowAvailability;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Value;

/** Everything the panel shows, built on the client thread and handed to Swing as one immutable value. */
@Value
@Builder(toBuilder = true)
public class PanelModel
{
	public static final PanelModel EMPTY = PanelModel.builder().state(PanelState.NO_CLUE).build();

	PanelState state;
	/** Replaces the state's own detail line (the dormant world's reason). */
	String statusDetail;
	/** The current sub-step, its hint and the one after; null without a route. */
	String now;
	String nowDetail;
	String next;
	@Builder.Default
	List<String> notes = List.of();
	@Builder.Default
	List<RouteCard> routes = List.of();
	@Builder.Default
	List<Plan.Option> why = List.of();

	@Value
	public static class RouteCard
	{
		String label;
		int ticks;
		String legs;
		/** "leaves you on Ancient · charges unknown"; empty when nothing to say. */
		String flags;
		/** The route the guide follows. */
		boolean followed;
	}

	/**
	 * @param state     from {@link PanelState#of}
	 * @param nowItemWorn the current sub-step's item is worn, so its option is in the equipment tab
	 * @param f2pBook   the book a member is stuck on while on an F2P world, or null
	 */
	public static PanelModel of(PanelState state, String dormantReason, Plan plan, Route committed, SubStep now, SubStep next,
		boolean nowItemWorn, Book f2pBook)
	{
		PanelModelBuilder model = PanelModel.builder().state(state);
		if (state == PanelState.DORMANT)
		{
			return model.statusDetail("Clue Helper stays off on this " + dormantReason + ".").build();
		}
		if (!state.showsRoutes() || plan == null)
		{
			return model.build();
		}
		if (now != null)
		{
			String detail = now.getDetail() == null && nowItemWorn ? "Worn: right-click it in the equipment tab" : now.getDetail();
			model.now(now.getText()).nowDetail(detail).next(next == null ? null : next.getText());
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
		List<RouteCard> cards = cards(plan, committed);
		if (cards.isEmpty() && notes.isEmpty())
		{
			notes.add(plan == Plan.NONE ? "Waiting for the first plan" : "No route from what you have");
		}
		return model.notes(notes).routes(cards).why(plan.getWhy() == null ? List.of() : plan.getWhy()).build();
	}

	/** The followed route first, then Best, Direct, Same book, the faster one under the margin and the alternatives. */
	static List<RouteCard> cards(Plan plan, Route committed)
	{
		List<RouteCard> cards = new ArrayList<>();
		Set<String> shown = new HashSet<>();
		Route best = plan.getBest();
		Route first = committed != null ? committed : best;
		if (first == null)
		{
			return cards;
		}
		boolean followingBest = best == null || best.getIdentity().equals(first.getIdentity());
		add(cards, shown, followingBest ? "Best" : "Following", first, null, true);
		add(cards, shown, "Best", best, first, false);
		add(cards, shown, "Direct", plan.getDirect(), first, false);
		add(cards, shown, "Same book", plan.getSameBook(), first, false);
		add(cards, shown, "Under the margin", plan.getFasterUnqualified(), first, false);
		for (Route alternative : plan.getAlternatives())
		{
			add(cards, shown, "Also", alternative, first, false);
		}
		return cards;
	}

	private static void add(List<RouteCard> cards, Set<String> shown, String label, Route route, Route reference, boolean followed)
	{
		if (route == null || !shown.add(route.getIdentity()))
		{
			return;
		}
		String saving = reference == null ? "" : savingText(route.getTicks() - reference.getTicks());
		cards.add(new RouteCard(label + saving, route.getTicks(), legs(route), flags(route), followed));
	}

	private static String savingText(int delta)
	{
		if (delta == 0)
		{
			return " · same";
		}
		return delta > 0 ? " · +" + delta : " · " + delta;
	}

	static String legs(Route route)
	{
		if (route.getLegs().isEmpty())
		{
			return "You're there";
		}
		return route.getLegs().stream().map(PanelModel::leg).collect(Collectors.joining(" → "));
	}

	private static String leg(Leg leg)
	{
		switch (leg.getKind())
		{
			case WALK:
				return "walk " + leg.getTicks();
			case WILDERNESS_EXIT:
				return "walk south " + leg.getTicks();
			case TELEPORT:
				return leg.getRow().getName();
			case SWAP_TELEPORT:
				return "Spellbook Swap → " + leg.getRow().getName();
			case FAIRY_RING:
				return leg.getLanding().getName();
			case SPELLBOOK_CHANGE:
				return changeName(leg) + " → " + bookName(leg.getBookAfter());
			default:
				return "bank";
		}
	}

	private static String changeName(Leg leg)
	{
		String name = leg.getChange().getName();
		int colon = name.indexOf(':');
		return (colon < 0 ? name : name.substring(0, colon)).replace(" (worn)", "");
	}

	static String flags(Route route)
	{
		List<String> flags = new ArrayList<>();
		if (route.getEndBook() != route.getStartBook())
		{
			flags.add("leaves you on " + bookName(route.getEndBook()));
		}
		if (route.getConfidence() == RowAvailability.Confidence.CHARGES_UNKNOWN)
		{
			flags.add("charges unknown");
		}
		else if (route.getConfidence() == RowAvailability.Confidence.UNREAD)
		{
			flags.add("not everything read yet");
		}
		if (route.isWilderness())
		{
			flags.add("Wilderness");
		}
		return String.join(" · ", flags);
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
