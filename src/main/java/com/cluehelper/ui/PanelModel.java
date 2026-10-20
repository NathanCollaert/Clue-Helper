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
import java.util.function.IntFunction;
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

	/** What the panel says about the remembered bank (PLAN §5.2 Bank). */
	@Value
	public static class BankInfo
	{
		public static final BankInfo NONE = new BankInfo(0, false, id -> "item " + id);

		/** When the bank was last seen (epoch millis); 0 for never. */
		long seenAt;
		/** No bank seen on this profile yet, and one would count: say "Open your bank once". */
		boolean promptOpen;
		IntFunction<String> itemNames;
	}

	/** What the panel says about the player's house (PLAN §5.2 POH, B16). */
	@Value
	public static class HouseInfo
	{
		public static final HouseInfo NONE = new HouseInfo(0, false, false);

		/** When an own-house scan last ran (epoch millis); 0 for never. */
		long scannedAt;
		/** The house is known only from the nexus varbits: say "Visit your house once". */
		boolean promptVisit;
		/** The furniture comes from the config's overrides. */
		boolean manual;
	}

	/**
	 * @param state     from {@link PanelState#of}
	 * @param nowItemWorn the current sub-step's item is worn, so its option is in the equipment tab
	 * @param f2pBook   the book a member is stuck on while on an F2P world, or null
	 */
	public static PanelModel of(PanelState state, String dormantReason, Plan plan, Route committed, SubStep now, SubStep next,
		boolean nowItemWorn, Book f2pBook)
	{
		return of(state, dormantReason, plan, committed, now, next, nowItemWorn, f2pBook, BankInfo.NONE, HouseInfo.NONE, null,
			System.currentTimeMillis());
	}

	/** @param menuHint replaces the sub-step's hint: the hotkey when another plugin hides the list line (B16) */
	public static PanelModel of(PanelState state, String dormantReason, Plan plan, Route committed, SubStep now, SubStep next,
		boolean nowItemWorn, Book f2pBook, BankInfo bank, HouseInfo house, String menuHint, long nowMillis)
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
			String detail = menuHint != null ? menuHint
				: now.getDetail() == null && nowItemWorn ? "Worn: right-click it in the equipment tab" : now.getDetail();
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
		notes.addAll(plan.getRequirementNotes());
		if (bank.isPromptOpen())
		{
			notes.add("Open your bank once so banked teleports count");
		}
		if (house.isPromptVisit())
		{
			notes.add("Visit your house once so its furniture counts");
		}
		List<RouteCard> cards = cards(plan, committed, bank, houseFlags(house, nowMillis), nowMillis);
		if (cards.isEmpty() && notes.isEmpty())
		{
			notes.add(plan == Plan.NONE ? "Waiting for the first plan" : "No route from what you have");
		}
		return model.notes(notes).routes(cards).why(plan.getWhy() == null ? List.of() : plan.getWhy()).build();
	}

	/** The followed route first, then Best, Direct, Same book, the faster one under the margin and the alternatives. */
	static List<RouteCard> cards(Plan plan, Route committed)
	{
		return cards(plan, committed, BankInfo.NONE, "", 0);
	}

	static List<RouteCard> cards(Plan plan, Route committed, BankInfo bank, String houseFlags, long nowMillis)
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
		String bankFlags = bankFlags(bank, nowMillis);
		add(cards, shown, followingBest ? "Best" : "Following", first, null, true, bankFlags, houseFlags, bank.getItemNames());
		add(cards, shown, "Best", best, first, false, bankFlags, houseFlags, bank.getItemNames());
		add(cards, shown, "Direct", plan.getDirect(), first, false, bankFlags, houseFlags, bank.getItemNames());
		add(cards, shown, "Same book", plan.getSameBook(), first, false, bankFlags, houseFlags, bank.getItemNames());
		add(cards, shown, "Under the margin", plan.getFasterUnqualified(), first, false, bankFlags, houseFlags, bank.getItemNames());
		for (Route alternative : plan.getAlternatives())
		{
			add(cards, shown, "Also", alternative, first, false, bankFlags, houseFlags, bank.getItemNames());
		}
		return cards;
	}

	private static void add(List<RouteCard> cards, Set<String> shown, String label, Route route, Route reference, boolean followed,
		String bankFlags, String houseFlags, IntFunction<String> itemNames)
	{
		if (route == null || !shown.add(route.getIdentity()))
		{
			return;
		}
		String saving = reference == null ? "" : savingText(route.getTicks() - reference.getTicks());
		String flags = flags(route);
		if (route.isBanked() && !bankFlags.isEmpty())
		{
			flags = flags.isEmpty() ? bankFlags : bankFlags + " · " + flags;
		}
		if (!houseFlags.isEmpty() && usesHouse(route))
		{
			flags = flags.isEmpty() ? houseFlags : houseFlags + " · " + flags;
		}
		cards.add(new RouteCard(label + saving, route.getTicks(), legs(route, itemNames), flags, followed));
	}

	/** "in bank, seen 3 days ago": the bank's age, the confidence of every banked route. */
	static String bankFlags(BankInfo bank, long nowMillis)
	{
		return bank.getSeenAt() == 0 ? "" : "in bank, seen " + age(nowMillis - bank.getSeenAt());
	}

	/** "house seen 3 days ago": how old the furniture is, on every route that uses the house. */
	static String houseFlags(HouseInfo house, long nowMillis)
	{
		if (house.isManual())
		{
			return "house set by hand";
		}
		return house.getScannedAt() == 0 ? "" : "house seen " + age(nowMillis - house.getScannedAt());
	}

	static boolean usesHouse(Route route)
	{
		return route.getLegs().stream().anyMatch(leg -> leg.getRow() != null && leg.getRow().getCategory().isHouse()
			|| leg.getChange() != null && leg.getChange().isInHouse()
			|| leg.getRow() != null && leg.getRow().getRequirements().getHouse() != null);
	}

	public static String age(long millis)
	{
		long minutes = millis / 60_000;
		if (minutes < 1)
		{
			return "just now";
		}
		if (minutes < 60)
		{
			return minutes + " min ago";
		}
		long hours = minutes / 60;
		if (hours < 24)
		{
			return hours + (hours == 1 ? " hour ago" : " hours ago");
		}
		long days = hours / 24;
		return days + (days == 1 ? " day ago" : " days ago");
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
		return legs(route, id -> "item " + id);
	}

	static String legs(Route route, IntFunction<String> itemNames)
	{
		String withdraw = withdrawText(route, itemNames);
		if (route.getLegs().isEmpty())
		{
			return route.isOpenBankStart() ? withdraw + " → you're there" : "You're there";
		}
		String legs = route.getLegs().stream().map(leg -> leg.getKind() == Leg.Kind.BANK ? "bank: " + withdraw : leg(leg))
			.collect(Collectors.joining(" → "));
		return route.isOpenBankStart() ? withdraw + " → " + legs : legs;
	}

	/** "withdraw Xeric's talisman + 2 clue items": items core's *clue* tag shows are only counted. */
	static String withdrawText(Route route, IntFunction<String> itemNames)
	{
		List<String> names = route.getWithdraw().stream().filter(use -> !route.getWithdrawInCoreTag().contains(use.getItemId()))
			.map(use -> itemNames.apply(use.getItemId())).collect(Collectors.toList());
		int tagged = route.getWithdraw().size() - names.size();
		if (tagged > 0)
		{
			names.add(tagged + " clue item" + (tagged == 1 ? "" : "s"));
		}
		return names.isEmpty() ? "withdraw nothing" : "withdraw " + String.join(" + ", names);
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
