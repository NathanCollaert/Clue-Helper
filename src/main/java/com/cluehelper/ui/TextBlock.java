package com.cluehelper.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;

/**
 * Text word-wrapped to a fixed width with the font's own metrics. Swing's HTML labels scale CSS
 * pixel widths, so their wrapped lines run past the label's edge and get cut off.
 */
public class TextBlock extends JComponent
{
	private final int wrapWidth;
	private String text = "";
	private List<String> lines = new ArrayList<>();

	public TextBlock(Font font, Color color, int wrapWidth)
	{
		this.wrapWidth = wrapWidth;
		setFont(font);
		setForeground(color);
		setAlignmentX(Component.LEFT_ALIGNMENT);
	}

	public String getText()
	{
		return text;
	}

	public void setText(String text)
	{
		this.text = text == null ? "" : text;
		lines = wrap(this.text, getFontMetrics(getFont()), wrapWidth);
		revalidate();
		repaint();
	}

	List<String> getLines()
	{
		return lines;
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(wrapWidth, lines.size() * getFontMetrics(getFont()).getHeight());
	}

	@Override
	public Dimension getMaximumSize()
	{
		return getPreferredSize();
	}

	@Override
	public Dimension getMinimumSize()
	{
		return getPreferredSize();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		FontMetrics fm = g.getFontMetrics(getFont());
		g.setFont(getFont());
		g.setColor(getForeground());
		int y = fm.getAscent();
		for (String line : lines)
		{
			g.drawString(line, 0, y);
			y += fm.getHeight();
		}
	}

	/** Greedy word wrap; a single word wider than the width gets a line of its own. */
	static List<String> wrap(String text, FontMetrics fm, int width)
	{
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.trim().split("\\s+"))
		{
			if (word.isEmpty())
			{
				continue;
			}
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (line.length() > 0 && fm.stringWidth(candidate) > width)
			{
				out.add(line.toString());
				line.setLength(0);
				line.append(word);
			}
			else
			{
				line.setLength(0);
				line.append(candidate);
			}
		}
		if (line.length() > 0)
		{
			out.add(line.toString());
		}
		return out;
	}
}
