package com.cluehelper;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The dev launcher's sidebar: the step campaign, mock clues from a list of step IDs, and dev tools as buttons.
 */
final class DevPanel extends PluginPanel
{
	private static final int GAP = 4;
	private static final int SECTION_GAP = 16;
	private static final int ROW_HEIGHT = 24;
	private static final int TEXT_LEFT = 8;
	private static final int ARC = 6;
	private static final Color ROW = new Color(46, 46, 46);
	private static final Color ROW_HOVER = new Color(60, 60, 60);
	private static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR;

	/** What the mock section drives; each runs on the client thread. */
	interface MockControls
	{
		/** {@code by} 1 for the next step ID of {@code ids}, -1 for the one before. */
		void walk(List<String> ids, int by);

		void clear();
	}

	/** What the campaign section drives. */
	interface CampaignControls
	{
		void finish();

		/** Fakes the game's reply to the guide's current act, which a mocked clue never gets. */
		void actDone();

		void bug(String note);

		void previous();

		void next();

		void stop();
	}

	/** A dev action: its label and what it runs. */
	static final class Action
	{
		final String label;
		final Runnable run;

		Action(String label, Runnable run)
		{
			this.label = label;
			this.run = run;
		}
	}

	private static final int LIST_ROWS = 7;
	private static final int NOTE_ROWS = 2;
	private static final String LINE = "\n";
	/** The label's HTML width, which the label measures larger than its pixels. */
	private static final int TEXT_WRAP_WIDTH = 165;

	private final JLabel current = new JLabel();
	private final JLabel campaignStep = new JLabel();
	private final JTextArea bugNote = new JTextArea(NOTE_ROWS, 0);
	private final JTextArea list = new JTextArea(LIST_ROWS, 0);

	DevPanel(CampaignControls campaign, MockControls mocks, List<Action> tools)
	{
		super(true);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setOpaque(false);
		campaignSection(column, campaign);
		column.add(Box.createVerticalStrut(SECTION_GAP));
		mockSection(column, mocks);
		column.add(Box.createVerticalStrut(SECTION_GAP));
		column.add(overline("DEV"));
		for (int i = 0; i < tools.size(); i++)
		{
			if (i > 0)
			{
				column.add(Box.createVerticalStrut(GAP));
			}
			column.add(new RowButton(tools.get(i)));
		}
		add(column, BorderLayout.NORTH);
		showMock(null);
		showCampaign("Starts once logged in");
	}

	/** Swing thread. The mocked clue's description, or null for none. */
	void showMock(String description)
	{
		setWrapped(current, description == null ? "No mock clue" : description);
	}

	/** Swing thread. */
	void showCampaign(String text)
	{
		setWrapped(campaignStep, text);
	}

	private static void setWrapped(JLabel label, String text)
	{
		String html = text.replace("&", "&amp;").replace("<", "&lt;").replace(LINE, "<br>");
		label.setText("<html><body style='width:" + TEXT_WRAP_WIDTH + "px'>" + html + "</body></html>");
	}

	private void campaignSection(JPanel column, CampaignControls campaign)
	{
		column.add(overline("CAMPAIGN"));
		campaignStep.setFont(FontManager.getRunescapeSmallFont());
		campaignStep.setForeground(Color.WHITE);
		campaignStep.setAlignmentX(LEFT_ALIGNMENT);
		column.add(campaignStep);
		column.add(Box.createVerticalStrut(GAP));
		column.add(row(new RowButton(new Action("Previous", campaign::previous)), new RowButton(new Action("Next", campaign::next)),
			new RowButton(new Action("Stop", campaign::stop))));
		column.add(Box.createVerticalStrut(GAP));
		column.add(row(new RowButton(new Action("Finish", campaign::finish)), new RowButton(new Action("Act done", campaign::actDone)),
			new RowButton(new Action("Bug", () ->
		{
			campaign.bug(bugNote.getText());
			if (!bugNote.getText().isBlank())
			{
				bugNote.setText("");
			}
		}))));
		column.add(Box.createVerticalStrut(GAP));
		column.add(overline("BUG NOTE"));
		bugNote.setFont(FontManager.getRunescapeSmallFont());
		bugNote.setLineWrap(true);
		bugNote.setWrapStyleWord(true);
		JScrollPane scroll = new JScrollPane(bugNote);
		scroll.setAlignmentX(LEFT_ALIGNMENT);
		scroll.setPreferredSize(new Dimension(PANEL_WIDTH - 16, NOTE_ROWS * ROW_HEIGHT));
		scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, NOTE_ROWS * ROW_HEIGHT));
		column.add(scroll);
	}

	private void mockSection(JPanel column, MockControls mocks)
	{
		column.add(overline("MOCK CLUES, ONE STEP ID PER LINE"));
		list.setFont(FontManager.getRunescapeSmallFont());
		list.setLineWrap(false);
		JScrollPane scroll = new JScrollPane(list);
		scroll.setAlignmentX(LEFT_ALIGNMENT);
		scroll.setPreferredSize(new Dimension(PANEL_WIDTH - 16, LIST_ROWS * ROW_HEIGHT * 2 / 3));
		scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, LIST_ROWS * ROW_HEIGHT * 2 / 3));
		column.add(scroll);
		column.add(Box.createVerticalStrut(GAP));
		column.add(row(new RowButton(new Action("Previous", () -> mocks.walk(lines(), -1))),
			new RowButton(new Action("Next", () -> mocks.walk(lines(), 1))),
			new RowButton(new Action("Clear", mocks::clear))));
		column.add(Box.createVerticalStrut(GAP));
		current.setFont(FontManager.getRunescapeSmallFont());
		current.setForeground(Color.WHITE);
		current.setAlignmentX(LEFT_ALIGNMENT);
		column.add(current);
	}

	private List<String> lines()
	{
		return List.of(list.getText().split(LINE));
	}

	private static JLabel overline(String title)
	{
		JLabel label = new JLabel(title);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(MUTED);
		label.setAlignmentX(LEFT_ALIGNMENT);
		label.setBorder(BorderFactory.createEmptyBorder(0, 0, GAP, 0));
		return label;
	}

	private static JComponent row(JComponent... cells)
	{
		JPanel row = new JPanel(new GridLayout(1, cells.length, GAP, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setPreferredSize(new Dimension(PANEL_WIDTH - 16, ROW_HEIGHT));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
		for (JComponent cell : cells)
		{
			row.add(cell);
		}
		return row;
	}

	/** A flat rounded button, its text ellipsized to fit. */
	private static final class RowButton extends JComponent
	{
		private final Action action;
		private boolean hover;

		RowButton(Action action)
		{
			this.action = action;
			setToolTipText(action.label);
			setAlignmentX(LEFT_ALIGNMENT);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setFont(FontManager.getRunescapeFont());
			setPreferredSize(new Dimension(PANEL_WIDTH - 16, ROW_HEIGHT));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
			setMinimumSize(new Dimension(0, ROW_HEIGHT));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					repaint();
				}

				@Override
				public void mouseClicked(MouseEvent e)
				{
					action.run.run();
				}
			});
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(hover ? ROW_HOVER : ROW);
			g.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
			g.setFont(getFont());
			g.setColor(Color.WHITE);
			FontMetrics metrics = g.getFontMetrics();
			String text = fit(action.label, metrics, getWidth() - 2 * TEXT_LEFT);
			g.drawString(text, TEXT_LEFT, (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2);
			g.dispose();
		}

		private static String fit(String text, FontMetrics metrics, int width)
		{
			if (metrics.stringWidth(text) <= width)
			{
				return text;
			}
			String cut = text;
			while (!cut.isEmpty() && metrics.stringWidth(cut + "…") > width)
			{
				cut = cut.substring(0, cut.length() - 1);
			}
			return cut + "…";
		}
	}

	/** A 16x16 orange badge in the item-sprite style: hard pixels, black outline. */
	static BufferedImage icon()
	{
		String[] grid = {
			"................",
			"..############..",
			".#oooooooooooo#.",
			".#oOOOOOOOOOOo#.",
			".#oO########Oo#.",
			".#oO#......#Oo#.",
			".#oO#.####.#Oo#.",
			".#oO#.#..#.#Oo#.",
			".#oO#.#..#.#Oo#.",
			".#oO#.####.#Oo#.",
			".#oO#......#Oo#.",
			".#oO########Oo#.",
			".#oOOOOOOOOOOo#.",
			".#oooooooooooo#.",
			"..############..",
			"................"};
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < grid.length; y++)
		{
			for (int x = 0; x < grid[y].length(); x++)
			{
				char c = grid[y].charAt(x);
				int argb = c == '#' ? 0xFF000000 : c == 'o' ? 0xFFDC8A00 : c == 'O' ? 0xFFFFB540 : 0;
				image.setRGB(x, y, argb);
			}
		}
		return image;
	}
}
