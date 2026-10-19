package com.cluehelper.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Colours, sizes and fonts of the panel, built on RuneLite's own palette. Spacing comes from one
 * 4px scale, and padding inside a box is never larger than the space around it.
 */
public final class Theme
{
	public static final Color CANVAS = ColorScheme.DARK_GRAY_COLOR;
	public static final Color SURFACE = new Color(46, 46, 46);
	public static final Color OUTLINE = new Color(60, 60, 60);
	public static final Color SKELETON = new Color(58, 58, 58);

	public static final Color TEXT = Color.WHITE;
	public static final Color TEXT_MUTED = ColorScheme.LIGHT_GRAY_COLOR;
	public static final Color TEXT_FAINT = new Color(120, 120, 120);
	public static final Color ACCENT = ColorScheme.BRAND_ORANGE;

	public static final int SPACE_XS = 4;
	public static final int SPACE_S = 8;
	public static final int SPACE_M = 12;

	public static final int ROW_HEIGHT = 32;
	public static final int ARC = 10;

	/** Around the panel's content and between cards. */
	public static final int SIDE = SPACE_S;
	public static final int GAP = SPACE_S;
	public static final int CARD_PAD = SPACE_S;
	public static final int CONTENT_WIDTH = PluginPanel.PANEL_WIDTH - 2 * SIDE;
	public static final int CARD_TEXT_WIDTH = CONTENT_WIDTH - 2 * CARD_PAD;

	public static Font title()
	{
		return FontManager.getRunescapeBoldFont();
	}

	public static Font body()
	{
		return FontManager.getRunescapeFont();
	}

	public static Font small()
	{
		return FontManager.getRunescapeSmallFont();
	}

	/** Antialiased copy of {@code g}; dispose it when done. */
	public static Graphics2D smooth(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		return g2;
	}

	private Theme()
	{
	}
}
