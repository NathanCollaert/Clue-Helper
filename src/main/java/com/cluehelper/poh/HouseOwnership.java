package com.cluehelper.poh;

import java.util.Locale;
import java.util.function.LongSupplier;
import lombok.*;

/**
 * A player can't log in inside a house, so every entry follows a click outside; build mode is a hard confirm. Anything
 * else is UNCERTAIN, which never learns. Clicks are classified by their option, not their target: the bank's
 * {@code Withdraw-All} on "Teleport to house" would otherwise read as an own entry.
 */
@RequiredArgsConstructor
public final class HouseOwnership
{
	/** Far longer than a click-to-load (1.6–5.9 s), far shorter than any other way into a house. */
	private static final long ENTRY_TTL_MILLIS = 15_000;

	public enum Ownership
	{
		OWNED, FOREIGN, UNCERTAIN
	}

	enum Entry
	{
		OWN, FRIEND, UNKNOWN
	}

	final LongSupplier clock;

	@Getter
	boolean inHouse;
	@Getter
	Ownership ownership = Ownership.UNCERTAIN;

	Entry pending = Entry.UNKNOWN;
	long pendingAt;

	/** Only clicks outside a house count: inside, a stranger's portal offers the owner's options. */
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
			pendingAt = clock.getAsLong();
		}
	}

	private static Entry classify(String option, String target)
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

	public void entered(boolean buildMode)
	{
		inHouse = true;
		boolean fresh = pending != Entry.UNKNOWN && clock.getAsLong() - pendingAt <= ENTRY_TTL_MILLIS;
		if (buildMode)
		{
			ownership = Ownership.OWNED;
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
	}

	/** One-way: build mode can prove "mine", never "not mine". */
	public void buildModeSeen()
	{
		if (inHouse)
		{
			ownership = Ownership.OWNED;
		}
	}

	public void startedInside(boolean buildMode)
	{
		inHouse = true;
		pending = Entry.UNKNOWN;
		ownership = buildMode ? Ownership.OWNED : Ownership.UNCERTAIN;
	}

	public void left()
	{
		inHouse = false;
		ownership = Ownership.UNCERTAIN;
	}

	private static String strip(String text)
	{
		return text == null ? "" : text.replaceAll("<[^>]*>", "").trim();
	}
}
