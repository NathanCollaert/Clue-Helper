package com.cluehelper.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.PluginPanel;

/** The sidebar panel. Swing thread only. */
public class ClueHelperPanel extends PluginPanel
{
	static final int PLACEHOLDER_ROUTES = 3;

	private final TextBlock statusTitle = new TextBlock(Theme.title(), Theme.TEXT, Theme.CARD_TEXT_WIDTH);
	private final TextBlock statusDetail = new TextBlock(Theme.body(), Theme.TEXT_MUTED, Theme.CARD_TEXT_WIDTH);
	private final JPanel statusCard = new Card();
	private final Component routeGap = Box.createVerticalStrut(Theme.GAP);
	private final JPanel routeSection = new JPanel();
	private volatile PanelState shownState;

	public ClueHelperPanel()
	{
		setBorder(new EmptyBorder(Theme.SIDE, Theme.SIDE, Theme.SIDE, Theme.SIDE));
		setBackground(Theme.CANVAS);
		setLayout(new BorderLayout());

		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setOpaque(false);
		column.add(buildStatusCard());
		column.add(routeGap);
		column.add(buildRouteSection());
		add(column, BorderLayout.NORTH);

		display(PanelState.NO_CLUE);
	}

	public void display(PanelState state)
	{
		if (state == shownState)
		{
			return;
		}
		shownState = state;
		boolean hasStatus = state.getTitle() != null;
		statusCard.setVisible(hasStatus);
		if (hasStatus)
		{
			statusTitle.setText(state.getTitle());
			statusDetail.setText(state.getDetail());
		}
		routeGap.setVisible(hasStatus && state.showsRoutes());
		routeSection.setVisible(state.showsRoutes());
		revalidate();
		repaint();
	}

	/** Readable from any thread. */
	public PanelState getShownState()
	{
		return shownState;
	}

	private JPanel buildStatusCard()
	{
		statusCard.setLayout(new BoxLayout(statusCard, BoxLayout.Y_AXIS));
		statusCard.setBorder(new EmptyBorder(Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD));
		statusCard.add(statusTitle);
		statusCard.add(Box.createVerticalStrut(Theme.SPACE_XS));
		statusCard.add(statusDetail);
		return statusCard;
	}

	private JPanel buildRouteSection()
	{
		routeSection.setLayout(new BoxLayout(routeSection, BoxLayout.Y_AXIS));
		routeSection.setOpaque(false);
		routeSection.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel overline = new JLabel("ROUTES");
		overline.setFont(Theme.small());
		overline.setForeground(Theme.TEXT_FAINT);
		overline.setBorder(new EmptyBorder(0, Theme.CARD_PAD, Theme.SPACE_XS, 0));
		routeSection.add(overline);
		for (int i = 1; i <= PLACEHOLDER_ROUTES; i++)
		{
			if (i > 1)
			{
				routeSection.add(Box.createVerticalStrut(Theme.SPACE_XS));
			}
			routeSection.add(placeholderRoute(i));
		}
		return routeSection;
	}

	private static JPanel placeholderRoute(int number)
	{
		JPanel row = new Card();
		row.setLayout(new BorderLayout());
		row.setBorder(new EmptyBorder(0, Theme.CARD_PAD, 0, Theme.CARD_PAD));
		row.setPreferredSize(new Dimension(Theme.CONTENT_WIDTH, Theme.ROW_HEIGHT));
		JLabel name = new JLabel("Route " + number);
		name.setFont(Theme.body());
		name.setForeground(Theme.TEXT_FAINT);
		JLabel ticks = new JLabel("– ticks");
		ticks.setFont(Theme.small());
		ticks.setForeground(Theme.TEXT_FAINT);
		row.add(name, BorderLayout.WEST);
		row.add(ticks, BorderLayout.EAST);
		return row;
	}

	private static final class Card extends JPanel
	{
		Card()
		{
			setOpaque(false);
			setAlignmentX(Component.LEFT_ALIGNMENT);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = Theme.smooth(g);
			g2.setColor(Theme.SURFACE);
			g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Theme.ARC, Theme.ARC);
			g2.setColor(Theme.OUTLINE);
			g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Theme.ARC, Theme.ARC);
			g2.dispose();
		}
	}
}
