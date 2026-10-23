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
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import net.runelite.api.Skill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/** The dev launcher's sidebar: mock clues (random by tier and type, or a list of step IDs) and the ::ch- commands as buttons. */
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
		void random(String tier, String type);

		void randomList(String tier, String type);

		/** {@code by} 1 for the next step ID of {@code ids}, -1 for the one before. */
		void walk(List<String> ids, int by);

		void clear();
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
	private static final int LEVEL_ROWS = 3;
	private static final String LINE = "\n";
	/** The label's HTML width, which the label measures larger than its pixels. */
	private static final int TEXT_WRAP_WIDTH = 165;

	private final JLabel current = new JLabel();
	private final JTextArea list = new JTextArea(LIST_ROWS, 0);
	private final JTextArea virtualLevels = new JTextArea(LEVEL_ROWS, 0);

	DevPanel(MockControls mocks, Consumer<Map<Skill, Integer>> levels, List<Action> tools)
	{
		super(true);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setOpaque(false);
		mockSection(column, mocks);
		column.add(Box.createVerticalStrut(SECTION_GAP));
		levelSection(column, levels);
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
	}

	/** Swing thread. The mocked clue's description, or null for none. */
	void showMock(String description)
	{
		String text = description == null ? "No mock clue" : description;
		String html = text.replace("&", "&amp;").replace("<", "&lt;").replace(LINE, "<br>");
		current.setText("<html><body style='width:" + TEXT_WRAP_WIDTH + "px'>" + html + "</body></html>");
	}

	/** Swing thread. */
	void fillList(List<String> ids)
	{
		list.setText(String.join(LINE, ids));
	}

	private void mockSection(JPanel column, MockControls mocks)
	{
		column.add(overline("MOCK CLUE"));
		JComboBox<String> tier = combo(MockClues.TIERS);
		JComboBox<String> type = combo(MockClues.TYPES);
		column.add(row(tier, type));
		column.add(Box.createVerticalStrut(GAP));
		column.add(row(
			new RowButton(new Action("Random clue", () -> mocks.random(selected(tier), selected(type)))),
			new RowButton(new Action("Random list", () -> mocks.randomList(selected(tier), selected(type))))));
		column.add(Box.createVerticalStrut(SECTION_GAP));
		column.add(overline("STEP IDS, ONE PER LINE"));
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

	/** Levels the plugin plans with instead of the account's, one SKILL=level per line: AGILITY=50. */
	private void levelSection(JPanel column, Consumer<Map<Skill, Integer>> levels)
	{
		column.add(overline("VIRTUAL LEVELS, SKILL=LEVEL"));
		virtualLevels.setFont(FontManager.getRunescapeSmallFont());
		JScrollPane scroll = new JScrollPane(virtualLevels);
		scroll.setAlignmentX(LEFT_ALIGNMENT);
		scroll.setPreferredSize(new Dimension(PANEL_WIDTH - 16, LEVEL_ROWS * ROW_HEIGHT * 2 / 3));
		scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, LEVEL_ROWS * ROW_HEIGHT * 2 / 3));
		column.add(scroll);
		column.add(Box.createVerticalStrut(GAP));
		column.add(row(new RowButton(new Action("Apply", () -> levels.accept(parseLevels(virtualLevels.getText())))),
			new RowButton(new Action("Clear", () ->
			{
				virtualLevels.setText("");
				levels.accept(Map.of());
			}))));
	}

	/** Lines that aren't SKILL=level are left out. */
	static Map<Skill, Integer> parseLevels(String text)
	{
		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		for (String line : text.split(LINE))
		{
			String[] parts = line.trim().split("\\s*=\\s*");
			Skill skill = parts.length != 2 ? null
				: Arrays.stream(Skill.values()).filter(each -> each.name().equalsIgnoreCase(parts[0])).findFirst().orElse(null);
			if (skill != null && parts[1].matches("\\d{1,3}"))
			{
				levels.put(skill, Integer.parseInt(parts[1]));
			}
		}
		return levels;
	}

	private List<String> lines()
	{
		return List.of(list.getText().split(LINE));
	}

	private static String selected(JComboBox<String> box)
	{
		Object item = box.getSelectedItem();
		return item == null ? MockClues.ANY : item.toString();
	}

	private static JComboBox<String> combo(List<String> items)
	{
		JComboBox<String> box = new JComboBox<>(items.toArray(new String[0]));
		box.setFont(FontManager.getRunescapeSmallFont());
		box.setFocusable(false);
		return box;
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
