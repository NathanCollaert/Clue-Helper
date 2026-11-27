package com.cluehelper.guide;

import javax.inject.*;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;

@Singleton
public class FairyDials
{
	private static final int COUNT = 3;
	private static final int LETTERS_PER_DIAL = 4;
	private static final int LOG_COMPONENTS_PER_CODE = 2;
	private static final String LETTERS = "ABCDIJKLPQRS";
	private static final int[] VARBITS = {VarbitID.FAIRYRING_1, VarbitID.FAIRYRING_2, VarbitID.FAIRYRING_3};

	@Inject
	Client client;

	boolean isDialled(String code)
	{
		String letters = letters(code);
		for (int dial = 0; letters != null && dial < COUNT; dial++)
		{
			if (turnsToFront(dial, letters.charAt(dial)) != 0)
			{
				return false;
			}
		}
		return letters != null;
	}

	static String letters(String code)
	{
		String letters = code == null ? "" : code.replaceAll("[^A-Za-z]", "").toUpperCase();
		if (letters.length() != COUNT)
		{
			return null;
		}
		for (int dial = 0; dial < COUNT; dial++)
		{
			if (varbitWithLetterAtFront(dial, letters.charAt(dial)) < 0)
			{
				return null;
			}
		}
		return letters;
	}

	/**
	 * The code a ring's last-destination varbit names: two bits per dial, the left dial highest, each counted as the
	 * dial's own varbit is.
	 */
	public static String code(int lastLocation)
	{
		StringBuilder code = new StringBuilder();
		for (int dial = 0; dial < COUNT; dial++)
		{
			int varbit = lastLocation >> 2 * (COUNT - 1 - dial) & LETTERS_PER_DIAL - 1;
			code.append(LETTERS.charAt(dial * LETTERS_PER_DIAL + (LETTERS_PER_DIAL - varbit) % LETTERS_PER_DIAL));
		}
		return code.toString();
	}

	/**
	 * The travel log row a fairy code target is dialled from: a chain's codes from the Fairy Queen's hideout row, which
	 * turns the dials to the chain's next code (most of them have no row of their own), else the code's own row.
	 */
	static int logRow(MenuTarget target, String letters)
	{
		return target.getLineName() != null ? InterfaceID.FairyringsLog.HIDEOUT : logRow(letters);
	}

	/** The travel log row of a code: every code in alphabetical order from AIP, each followed by its favourite star. */
	static int logRow(String letters)
	{
		int index = 0;
		for (int dial = 0; dial < COUNT; dial++)
		{
			index = index * LETTERS_PER_DIAL + LETTERS.indexOf(letters.charAt(dial)) - dial * LETTERS_PER_DIAL;
		}
		return InterfaceID.FairyringsLog.AIP + LOG_COMPONENTS_PER_CODE * index;
	}

	/**
	 * Each dial's varbit counts its letters in the order they reach the front turning clockwise: A, D, C, B (I, L, K, J;
	 * P, S, R, Q). -1 when the letter isn't on that dial.
	 */
	private static int varbitWithLetterAtFront(int dial, char letter)
	{
		int onDial = LETTERS.indexOf(letter) - dial * LETTERS_PER_DIAL;
		return onDial < 0 || onDial >= LETTERS_PER_DIAL ? -1 : (LETTERS_PER_DIAL - onDial) % LETTERS_PER_DIAL;
	}

	private int turnsToFront(int dial, char letter)
	{
		return Math.floorMod(varbitWithLetterAtFront(dial, letter) - client.getVarbitValue(VARBITS[dial]), LETTERS_PER_DIAL);
	}
}
