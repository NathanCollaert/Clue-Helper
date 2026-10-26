package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.ClueHelperPlugin;
import com.cluehelper.hotcold.HotColdView;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/** The guidance text, under the Clue Scroll plugin's own box and as wide. Renders only the value it was handed. */
@Singleton
public class GuideOverlay extends OverlayPanel
{
	/** Core's box has the lowest named priority; the lower one stacks under it. */
	private static final float BELOW_CORE_CLUE_BOX = PRIORITY_LOW - 0.01f;
	private static final String TITLE = "Clue Helper";
	private static final Color TEXT = Color.WHITE;
	private static final Color MUTED = new Color(190, 190, 190);
	private static final Color WARNING = new Color(255, 170, 60);
	/** Dimmer than the item lines, so the step after this one reads as not yet. */
	private static final Color UPCOMING = new Color(135, 135, 135);
	private static final char NO_BREAK = ' ';

	private final ClueHelperConfig config;
	private volatile GuideBox box = GuideBox.EMPTY;
	private FontMetrics metrics;

	@Inject
	GuideOverlay(ClueHelperPlugin plugin, ClueHelperConfig config)
	{
		super(plugin);
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(BELOW_CORE_CLUE_BOX);
		addMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, OverlayManager.OPTION_CONFIGURE, "Clue Helper overlay");
	}

	/** Any thread. */
	public void show(GuideBox box)
	{
		this.box = box;
	}

	/** Readable from any thread. */
	public GuideBox getShown()
	{
		return box;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		GuideBox shown = box;
		if (!shown.isShown() || !config.showBox())
		{
			return null;
		}
		panelComponent.setPreferredSize(new Dimension(ComponentConstants.STANDARD_WIDTH, 0));
		metrics = graphics.getFontMetrics();
		panelComponent.getChildren().add(TitleComponent.builder().text(TITLE).color(TEXT).build());
		line(shown.title(), TEXT);
		line(shown.detail(), MUTED);
		line(shown.getNow(), TEXT);
		shown.getNowItems().forEach(item -> line("- " + item, MUTED));
		line(shown.getNowDetail(), MUTED);
		if (shown.getHotCold() != null)
		{
			gap();
			hotCold(shown.getHotCold());
		}
		if (!shown.getNotes().isEmpty())
		{
			gap();
			shown.getNotes().forEach(note -> line(note, WARNING));
		}
		if (shown.getNext() != null)
		{
			gap();
			panelComponent.getChildren().add(LineComponent.builder().left(closeColours(shown.getNext(), UPCOMING)).leftColor(UPCOMING)
				.leftFont(FontManager.getRunescapeSmallFont()).build());
		}
		return super.render(graphics);
	}

	private void hotCold(HotColdView view)
	{
		line(view.getTitle(), TEXT);
		view.getWarnings().forEach(warning -> line(warning, WARNING));
	}

	private void gap()
	{
		panelComponent.getChildren().add(LineComponent.builder().left("").build());
	}

	private void line(String text, Color colour)
	{
		if (text == null || text.isEmpty())
		{
			return;
		}
		for (String part : text.contains("<col") ? wrapTagged(text) : List.of(text))
		{
			panelComponent.getChildren().add(LineComponent.builder().left(closeColours(part, colour)).leftColor(colour).build());
		}
	}

	/**
	 * LineComponent measures a word with its colour tags when it wraps, so a coloured hotkey wraps a line early. Such a
	 * line is wrapped here on its visible text, each piece held together by non-breaking spaces.
	 */
	private List<String> wrapTagged(String text)
	{
		int width = ComponentConstants.STANDARD_WIDTH - 2 * ComponentConstants.STANDARD_BORDER;
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String joined = line.length() == 0 ? word : line + " " + word;
			if (line.length() > 0 && metrics.stringWidth(Text.removeTags(joined)) > width)
			{
				lines.add(line.toString().replace(' ', NO_BREAK));
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(joined);
			}
		}
		lines.add(line.toString().replace(' ', NO_BREAK));
		return lines;
	}

	/** The box's text knows no closing colour tag: a coloured hotkey switches back to the line's own colour. */
	private static String closeColours(String text, Color colour)
	{
		return text.replace("</col>", ColorUtil.colorTag(colour));
	}
}
