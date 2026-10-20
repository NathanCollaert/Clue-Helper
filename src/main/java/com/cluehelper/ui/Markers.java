package com.cluehelper.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Our marker style: a cyan diamond and a downward chevron, so nothing of ours can be mistaken for the Clue
 * Scroll plugin's yellow arrow ({@code clue_arrow.png}).
 */
final class Markers
{
	static final Color COLOUR = new Color(64, 208, 255);
	static final Color FILL = new Color(64, 208, 255, 40);
	/** An item or object whose left click teleports straight there. */
	static final Color ONE_CLICK_FILL = new Color(64, 208, 255, 90);
	static final Color OUTLINE = new Color(0, 0, 0, 200);
	private static final int DIAMOND = 14;
	private static final int ARROW_WIDTH = 18;
	private static final int ARROW_HEIGHT = 22;

	static final BufferedImage DIAMOND_IMAGE = diamond();
	static final BufferedImage ARROW_IMAGE = arrow();

	private Markers()
	{
	}

	private static BufferedImage diamond()
	{
		BufferedImage image = new BufferedImage(DIAMOND, DIAMOND, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = smooth(image);
		int m = DIAMOND / 2;
		Polygon shape = new Polygon(new int[]{m, DIAMOND - 2, m, 1}, new int[]{1, m, DIAMOND - 2, m}, 4);
		g.setColor(COLOUR);
		g.fillPolygon(shape);
		g.setColor(OUTLINE);
		g.setStroke(new BasicStroke(1.5f));
		g.drawPolygon(shape);
		g.dispose();
		return image;
	}

	private static BufferedImage arrow()
	{
		BufferedImage image = new BufferedImage(ARROW_WIDTH, ARROW_HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = smooth(image);
		int m = ARROW_WIDTH / 2;
		Polygon shape = new Polygon(
			new int[]{1, m, ARROW_WIDTH - 2, m},
			new int[]{1, 7, 1, ARROW_HEIGHT - 2}, 4);
		g.setColor(COLOUR);
		g.fillPolygon(shape);
		g.setColor(OUTLINE);
		g.setStroke(new BasicStroke(1.5f));
		g.drawPolygon(shape);
		g.dispose();
		return image;
	}

	private static Graphics2D smooth(BufferedImage image)
	{
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		return g;
	}
}
