package com.cluehelper.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Cyan, so nothing of ours can be mistaken for the Clue Scroll plugin's yellow arrow. */
final class Markers
{
	static final Color COLOUR = new Color(64, 208, 255);
	static final Color FILL = new Color(64, 208, 255, 40);
	static final Color ONE_CLICK_FILL = new Color(64, 208, 255, 90);
	static final Color OUTLINE = new Color(0, 0, 0, 200);
	private static final int DIAMOND = 14;

	static final BufferedImage DIAMOND_IMAGE = diamond();

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

	private static Graphics2D smooth(BufferedImage image)
	{
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		return g;
	}
}
