package com.cluehelper.poh;

import java.util.Locale;
import java.util.function.LongSupplier;
import lombok.Getter;

/**
 * Whose house the player is in (Estate-Agent's entry tracking, PLAN §5.2 POH, B12). You can never be logged in inside
 * a house, so every entry follows a click outside: the last classified click within 15 s of the house loading decides,
 * and build mode is a hard confirm. Anything else is UNCERTAIN, which never learns.
 * <p>
 * Clicks are classified by their <b>option</b>: B12 saw the bank's {@code Withdraw-All} on "Teleport to house" read as an
 * own entry when the target text was matched.
 */
public final class HouseOwnership
{
	/** Far longer than a click-to-load (1.6–5.9 s in B12), far shorter than any other way into a house. */
	static final long ENTRY_TTL_MILLIS = 15_000;

	public enum Ownership
	{
		OWNED, FOREIGN, UNCERTAIN
	}

	enum Entry
	{
		OWN, FRIEND, UNKNOWN
	}

	private final LongSupplier clock;

	@Getter
	private boolean inHouse;
	@Getter
	private Ownership ownership = Ownership.UNCERTAIN;
	/** The click that decided the current visit, for the log. */
	@Getter
	private String entryClick;

	private Entry pending = Entry.UNKNOWN;
	private String pendingClick;
	private long pendingAt;

	public HouseOwnership(LongSupplier clock)
	{
		this.clock = clock;
	}

	/** Every menu click; only clicks outside a house count (inside, a stranger's portal offers the owner's options). */
	public void observeClick(String option, String target)
	{
		if (inHouse)
		{
			return;
		}
		Entry entry = classify(option, target);
		if (entry != Entry.UNKNOWN)
		{
			pending = entry;
			pendingClick = (strip(option) + " " + strip(target)).trim();
			pendingAt = clock.getAsLong();
		}
	}

	static Entry classify(String option, String target)
	{
		String op = strip(option).toLowerCase(Locale.ROOT);
		String on = strip(target).toLowerCase(Locale.ROOT);
		switch (op)
		{
			case "home":
			case "build mode":
			case "tele to poh":
				return Entry.OWN;
			case "break":
			case "cast":
				return on.contains("teleport to house") ? Entry.OWN : Entry.UNKNOWN;
			case "visit-last":
			case "friend's house":
			case "enter house":
				return Entry.FRIEND;
			case "view":
				return on.contains("house advertisement") ? Entry.FRIEND : Entry.UNKNOWN;
			default:
				return Entry.UNKNOWN;
		}
	}

	/** The house scene loaded. */
	public void entered(boolean buildMode)
	{
		inHouse = true;
		boolean fresh = pending != Entry.UNKNOWN && clock.getAsLong() - pendingAt <= ENTRY_TTL_MILLIS;
		entryClick = fresh ? pendingClick : null;
		if (buildMode)
		{
			ownership = Ownership.OWNED;
			entryClick = "build mode";
		}
		else if (!fresh)
		{
			ownership = Ownership.UNCERTAIN;
		}
		else
		{
			ownership = pending == Entry.OWN ? Ownership.OWNED : Ownership.FOREIGN;
		}
		pending = Entry.UNKNOWN;
		pendingClick = null;
	}

	/** Build mode seen during the visit: one-way, it can prove "mine" and never "not mine". */
	public void buildModeSeen()
	{
		if (inHouse)
		{
			ownership = Ownership.OWNED;
			entryClick = "build mode";
		}
	}

	/** The plugin started (or the client logged in) with the player already inside: nothing was seen. */
	public void startedInside(boolean buildMode)
	{
		inHouse = true;
		pending = Entry.UNKNOWN;
		ownership = buildMode ? Ownership.OWNED : Ownership.UNCERTAIN;
		entryClick = buildMode ? "build mode" : null;
	}

	public void left()
	{
		inHouse = false;
		ownership = Ownership.UNCERTAIN;
		entryClick = null;
	}

	private static String strip(String text)
	{
		return text == null ? "" : text.replaceAll("<[^>]*>", "").trim();
	}
}
