package com.cluehelper.ui;

import com.cluehelper.route.Plan;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.PluginPanel;

/** Swing thread only. */
public class ClueHelperPanel extends PluginPanel
{
	static final int WHY_ROWS = 20;
	private static final Color WARNING = new Color(255, 170, 60);

	private final TextBlock statusTitle = new TextBlock(Theme.title(), Theme.TEXT, Theme.CARD_TEXT_WIDTH);
	private final TextBlock statusDetail = new TextBlock(Theme.body(), Theme.TEXT_MUTED, Theme.CARD_TEXT_WIDTH);
	private final JPanel statusCard = new Card();
	private final TextBlock now = new TextBlock(Theme.title(), Theme.TEXT, Theme.CARD_TEXT_WIDTH);
	private final TextBlock nowDetail = new TextBlock(Theme.small(), Theme.TEXT_MUTED, Theme.CARD_TEXT_WIDTH);
	private final TextBlock next = new TextBlock(Theme.small(), Theme.TEXT_FAINT, Theme.CARD_TEXT_WIDTH);
	private final JPanel guideCard = new Card();
	private final Component guideGap = Box.createVerticalStrut(Theme.GAP);
	private final JPanel column = new JPanel();
	private final JPanel dynamic = new JPanel();
	private PanelModel shown;
	private volatile PanelState shownState;
	private boolean whyOpen;

	public ClueHelperPanel()
	{
		setBorder(new EmptyBorder(Theme.SIDE, Theme.SIDE, Theme.SIDE, Theme.SIDE));
		setBackground(Theme.CANVAS);
		setLayout(new BorderLayout());

		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setOpaque(false);
		column.add(buildStatusCard());
		column.add(guideGap);
		column.add(buildGuideCard());
		dynamic.setLayout(new BoxLayout(dynamic, BoxLayout.Y_AXIS));
		dynamic.setOpaque(false);
		dynamic.setAlignmentX(Component.LEFT_ALIGNMENT);
		column.add(dynamic);
		add(column, BorderLayout.NORTH);

		show(PanelModel.EMPTY);
	}

	public void display(PanelState state)
	{
		show((shown == null ? PanelModel.EMPTY : shown).toBuilder().state(state).build());
	}

	public void show(PanelModel model)
	{
		if (model.equals(shown))
		{
			return;
		}
		shown = model;
		shownState = model.getState();
		PanelState state = model.getState();
		boolean hasStatus = state.getTitle() != null;
		statusCard.setVisible(hasStatus);
		if (hasStatus)
		{
			statusTitle.setText(state.getTitle());
			statusDetail.setText(model.getStatusDetail() != null ? model.getStatusDetail() : state.getDetail());
		}
		boolean routes = state.showsRoutes();
		guideCard.setVisible(routes && model.getNow() != null);
		guideGap.setVisible(hasStatus && guideCard.isVisible());
		if (model.getNow() != null)
		{
			now.setText(model.getNow());
			nowDetail.setText(model.getNowDetail());
			nowDetail.setVisible(model.getNowDetail() != null);
			next.setText(model.getNext() == null ? null : "Then: " + model.getNext());
			next.setVisible(model.getNext() != null);
		}
		rebuildDynamic(routes ? model : null, hasStatus || guideCard.isVisible());
		revalidate();
		repaint();
	}

	void toggleWhy()
	{
		whyOpen = !whyOpen;
		PanelModel model = shown;
		shown = null;
		show(model);
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

	private JPanel buildGuideCard()
	{
		guideCard.setLayout(new BoxLayout(guideCard, BoxLayout.Y_AXIS));
		guideCard.setBorder(new EmptyBorder(Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD));
		guideCard.add(overline("NOW", Theme.ACCENT, 0));
		guideCard.add(now);
		guideCard.add(nowDetail);
		guideCard.add(Box.createVerticalStrut(Theme.SPACE_XS));
		guideCard.add(next);
		return guideCard;
	}

	/** Notes, route cards and "Show why" change with every plan, so they are built fresh. */
	private void rebuildDynamic(PanelModel model, boolean somethingAbove)
	{
		dynamic.removeAll();
		if (model == null)
		{
			return;
		}
		boolean first = !somethingAbove;
		for (String note : model.getNotes())
		{
			first = gap(first);
			TextBlock line = new TextBlock(Theme.small(), WARNING, Theme.CARD_TEXT_WIDTH);
			line.setText(note);
			dynamic.add(indent(line));
		}
		if (!model.getRoutes().isEmpty())
		{
			first = gap(first);
			dynamic.add(overline("ROUTES", Theme.TEXT_FAINT, Theme.CARD_PAD));
			for (int i = 0; i < model.getRoutes().size(); i++)
			{
				if (i > 0)
				{
					dynamic.add(Box.createVerticalStrut(Theme.SPACE_XS));
				}
				dynamic.add(routeCard(model.getRoutes().get(i)));
			}
		}
		if (!model.getWhy().isEmpty())
		{
			gap(first);
			dynamic.add(whyToggle());
			if (whyOpen)
			{
				dynamic.add(Box.createVerticalStrut(Theme.SPACE_XS));
				dynamic.add(whyCard(model.getWhy()));
			}
		}
	}

	private boolean gap(boolean first)
	{
		if (!first)
		{
			dynamic.add(Box.createVerticalStrut(Theme.GAP));
		}
		return false;
	}

	private static JPanel routeCard(PanelModel.RouteCard route)
	{
		JPanel card = new Card();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBorder(new EmptyBorder(Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD));
		JPanel header = new JPanel(new BorderLayout(Theme.SPACE_S, 0));
		header.setOpaque(false);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(label(route.getLabel(), Theme.small(), route.isFollowed() ? Theme.ACCENT : Theme.TEXT_MUTED), BorderLayout.CENTER);
		header.add(label("≈ " + route.getTicks() + " ticks", Theme.small(), Theme.TEXT_MUTED), BorderLayout.EAST);
		header.setMaximumSize(new Dimension(Theme.CARD_TEXT_WIDTH, header.getPreferredSize().height));
		card.add(header);
		card.add(Box.createVerticalStrut(Theme.SPACE_XS));
		TextBlock legs = new TextBlock(Theme.body(), Theme.TEXT, Theme.CARD_TEXT_WIDTH);
		legs.setText(route.getLegs());
		card.add(legs);
		if (!route.getFlags().isEmpty())
		{
			TextBlock flags = new TextBlock(Theme.small(), Theme.TEXT_FAINT, Theme.CARD_TEXT_WIDTH);
			flags.setText(route.getFlags());
			card.add(flags);
		}
		return card;
	}

	private JComponent whyToggle()
	{
		JLabel toggle = label(whyOpen ? "Hide why" : "Show why: every transport", Theme.small(), Theme.ACCENT);
		toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		toggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggleWhy();
			}
		});
		return indent(toggle);
	}

	private static JPanel whyCard(List<Plan.Option> why)
	{
		JPanel card = new Card();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBorder(new EmptyBorder(Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD));
		TextBlock intro = new TextBlock(Theme.small(), Theme.TEXT_FAINT, Theme.CARD_TEXT_WIDTH);
		intro.setText("Teleport plus walk, from what you carry on this book. Fairy rings (*) add the walk to a ring.");
		card.add(intro);
		card.add(Box.createVerticalStrut(Theme.SPACE_XS));
		for (Plan.Option option : why.subList(0, Math.min(WHY_ROWS, why.size())))
		{
			JPanel row = new JPanel(new BorderLayout(Theme.SPACE_S, 0));
			row.setOpaque(false);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.add(label((option.isFairyRing() ? "* " : "") + option.getName(), Theme.small(), Theme.TEXT), BorderLayout.CENTER);
			row.add(label(option.getTicks() + " t", Theme.small(), Theme.TEXT_MUTED), BorderLayout.EAST);
			row.setMaximumSize(new Dimension(Theme.CARD_TEXT_WIDTH, row.getPreferredSize().height));
			card.add(row);
		}
		if (why.size() > WHY_ROWS)
		{
			card.add(label("and " + (why.size() - WHY_ROWS) + " slower", Theme.small(), Theme.TEXT_FAINT));
		}
		return card;
	}

	private static JLabel overline(String text, Color colour, int left)
	{
		JLabel label = label(text, Theme.small(), colour);
		label.setBorder(new EmptyBorder(0, left, Theme.SPACE_XS, 0));
		return label;
	}

	private static JLabel label(String text, java.awt.Font font, Color colour)
	{
		JLabel label = new JLabel(text);
		label.setFont(font);
		label.setForeground(colour);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setMinimumSize(new Dimension(0, label.getPreferredSize().height));
		return label;
	}

	/** Lines outside a card sit on the cards' text column. */
	private static JComponent indent(JComponent line)
	{
		JPanel holder = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		holder.setOpaque(false);
		holder.setAlignmentX(Component.LEFT_ALIGNMENT);
		holder.setBorder(new EmptyBorder(0, Theme.CARD_PAD, 0, Theme.CARD_PAD));
		holder.add(line, BorderLayout.WEST);
		return holder;
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
