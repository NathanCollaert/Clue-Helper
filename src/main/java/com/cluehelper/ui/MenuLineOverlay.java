package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.*;
import com.cluehelper.transport.MenuLine;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.*;

public class MenuLineOverlay extends Overlay
{
	@Inject
	StepGuide guide;
	@Inject
	MenuLines lines;
	@Inject
	FairyDials dials;
	@Inject
	ClueHelperConfig config;
	@Inject
	Highlighter highlighter;

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
		if (target.getLine() == MenuLine.FAIRY_CODE)
		{
			dials.shapes(target.getLineKey()).forEach(shape -> highlighter.box(graphics, shape));
		}
		return null;
	}
}
