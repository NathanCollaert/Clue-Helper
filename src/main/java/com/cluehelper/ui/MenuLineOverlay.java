package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.*;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.*;

public class MenuLineOverlay extends Overlay
{
	@Inject
	private StepGuide guide;
	@Inject
	private MenuLines lines;
	@Inject
	private FairyDials dials;
	@Inject
	private ClueHelperConfig config;
	@Inject
	private Highlighter highlighter;

	MenuLineOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		MenuTarget target = guide.getHighlights().getMenuTarget();
		if (!config.highlightMenus() || target.isEmpty())
		{
			return null;
		}
		for (Rectangle line : lines.lines(target))
		{
			highlighter.box(graphics, line);
		}
		if (target.getLine() == MenuTarget.Line.FAIRY_CODE)
		{
			dials.shapes(target.getLineKey()).forEach(shape -> highlighter.box(graphics, shape));
		}
		return null;
	}
}
