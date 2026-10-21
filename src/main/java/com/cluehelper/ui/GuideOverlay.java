package com.cluehelper.ui;

import com.cluehelper.ClueHelperPlugin;
import com.cluehelper.hotcold.HotColdView;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
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

	private volatile GuideBox box = GuideBox.EMPTY;

	@Inject
	GuideOverlay(ClueHelperPlugin plugin)
	{
		super(plugin);
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
		if (!shown.isShown())
		{
			return null;
		}
		panelComponent.setPreferredSize(new Dimension(ComponentConstants.STANDARD_WIDTH, 0));
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
			panelComponent.getChildren().add(LineComponent.builder().left(shown.getNext()).leftColor(UPCOMING)
				.leftFont(FontManager.getRunescapeSmallFont()).build());
		}
		return super.render(graphics);
	}

	private void hotCold(HotColdView view)
	{
		line(view.getTitle(), TEXT);
		List<String> lines = view.getLines();
		for (int i = 0; i < lines.size(); i++)
		{
			line(lines.get(i), i == 0 ? MUTED : TEXT);
		}
		view.getWarnings().forEach(warning -> line(warning, WARNING));
	}

	private void gap()
	{
		panelComponent.getChildren().add(LineComponent.builder().left("").build());
	}

	private void line(String text, Color colour)
	{
		if (text != null && !text.isEmpty())
		{
			panelComponent.getChildren().add(LineComponent.builder().left(text).leftColor(colour).build());
		}
	}
}
