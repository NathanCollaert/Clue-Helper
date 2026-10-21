package com.cluehelper.guide;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * A code on the fairy ring interface (398): each dial's code letter circled on its mushroom, also once it is at the
 * front, never the turn buttons (Nathan). Whether all three show it, for the teleport button's blue text
 * ({@link MenuLines}). Each dial's varbit counts its letters in the order they reach the front as it turns clockwise:
 * A, D, C, B (I, L, K, J; P, S, R, Q). Read at draw time.
 */
@Singleton
public class FairyDials
{
	private static final int COUNT = 3;
	private static final int LETTERS_PER_DIAL = 4;
	private static final String LETTERS = "ABCDIJKLPQRS";
	private static final int[] VARBITS = {VarbitID.FAIRYRING_1, VarbitID.FAIRYRING_2, VarbitID.FAIRYRING_3};
	/**
	 * A dial's four letter widgets share one box on its hub, and the game turns each letter's model into place. So a
	 * mushroom is found from the hub by how many clockwise turns would bring it to the front: front, right, top, left.
	 * Measured live on 1.13.2; the front one shows through a hole in the plank.
	 */
	private static final Point[] MUSHROOM_OFFSETS = {new Point(0, 48), new Point(49, -1), new Point(0, -49), new Point(-49, -1)};
	private static final int[] MUSHROOM_RADII = {21, 31, 31, 31};

	private final Client client;

	@Inject
	FairyDials(Client client)
	{
		this.client = client;
	}

	/** Nothing while the interface is closed or the code isn't one. */
	public List<Shape> shapes(String code)
	{
		List<Shape> out = new ArrayList<>();
		String letters = letters(code);
		for (int dial = 0; letters != null && dial < COUNT; dial++)
		{
			char letter = letters.charAt(dial);
			Widget hub = client.getWidget(InterfaceID.Fairyrings.A + LETTERS.indexOf(letter));
			if (visible(hub))
			{
				out.add(mushroom(hub.getBounds(), turnsToFront(dial, letter)));
			}
		}
		return out;
	}

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

	/** "CKS" or "C K S" to its three letters, or null when it isn't a ring code. */
	static String letters(String code)
	{
		String letters = code == null ? "" : code.replaceAll("[^A-Za-z]", "").toUpperCase();
		if (letters.length() != COUNT)
		{
			return null;
		}
		for (int dial = 0; dial < COUNT; dial++)
		{
			if (position(dial, letters.charAt(dial)) < 0)
			{
				return null;
			}
		}
		return letters;
	}

	/** The code's line in the travel log, which lists every code alphabetically from AIP. */
	static int logIndex(String letters)
	{
		int index = 0;
		for (int dial = 0; dial < COUNT; dial++)
		{
			index = index * LETTERS_PER_DIAL + LETTERS.indexOf(letters.charAt(dial)) - dial * LETTERS_PER_DIAL;
		}
		return index;
	}

	/** The dial's varbit value while {@code letter} is at the front, or -1 when the letter isn't on that dial. */
	private static int position(int dial, char letter)
	{
		int onDial = LETTERS.indexOf(letter) - dial * LETTERS_PER_DIAL;
		return onDial < 0 || onDial >= LETTERS_PER_DIAL ? -1 : (LETTERS_PER_DIAL - onDial) % LETTERS_PER_DIAL;
	}

	/** Clockwise turns that would bring the letter to the front. */
	private int turnsToFront(int dial, char letter)
	{
		return Math.floorMod(position(dial, letter) - client.getVarbitValue(VARBITS[dial]), LETTERS_PER_DIAL);
	}

	private static Shape mushroom(Rectangle hub, int turnsToFront)
	{
		Point offset = MUSHROOM_OFFSETS[turnsToFront];
		int radius = MUSHROOM_RADII[turnsToFront];
		return new Ellipse2D.Double(hub.getCenterX() + offset.x - radius, hub.getCenterY() + offset.y - radius, 2 * radius, 2 * radius);
	}

	private static boolean visible(Widget widget)
	{
		return widget != null && !widget.isHidden() && widget.getBounds() != null && widget.getBounds().width > 0;
	}
}
