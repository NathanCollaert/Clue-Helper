package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.FairyDials;
import com.cluehelper.guide.MenuLines;
import com.cluehelper.guide.MenuTarget;
import com.cluehelper.guide.StepGuide;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

public class MenuLineOverlay extends Overlay
{
	private final StepGuide guide;
	private final MenuLines lines;
	private final FairyDials dials;
	private final ClueHelperConfig config;
	private final Highlighter highlighter;

	@Inject
	MenuLineOverlay(StepGuide guide, MenuLines lines, FairyDials dials, ClueHelperConfig config, Highlighter highlighter)
	{
		this.highlighter = highlighter;
		this.guide = guide;
		this.lines = lines;
		this.dials = dials;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		MenuTarget target = guide.getMenuTarget();
		if (!config.highlightMenus() || target == null || target.isEmpty())
		{
			return null;
		}
		for (Rectangle line : lines.lines(target))
		{
			highlighter.box(graphics, line);
		}
		for (MenuLines.KeyBadge badge : lines.keyBadges(target))
		{
			highlighter.keyBadge(graphics, badge.getKey(), badge.getTextEnd(), badge.getCentreY(), badge.getVisible());
		}
		if (target.getLine() == MenuTarget.Line.FAIRY_CODE)
		{
			dials.shapes(target.getLineKey()).forEach(shape -> highlighter.box(graphics, shape));
		}
		return null;
	}
}
