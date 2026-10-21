package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.FairyDials;
import com.cluehelper.guide.MenuLines;
import com.cluehelper.guide.MenuTarget;
import com.cluehelper.guide.StepGuide;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The current sub-step's interface lines that have no text to colour (B16): nexus map icons and region shields, the
 * fairy ring travel log, and the fairy ring dials ({@link FairyDials}). Text lines and menu options are coloured instead
 * ({@link MenuLines}). Always on top.
 */
public class MenuLineOverlay extends Overlay
{
	private static final int ARC = 6;
	private static final float STROKE = 2f;

	private final StepGuide guide;
	private final MenuLines lines;
	private final FairyDials dials;
	private final ClueHelperConfig config;

	@Inject
	MenuLineOverlay(StepGuide guide, MenuLines lines, FairyDials dials, ClueHelperConfig config)
	{
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
		graphics.setStroke(new BasicStroke(STROKE));
		for (Rectangle line : lines.lines(target))
		{
			draw(graphics, new RoundRectangle2D.Double(line.x, line.y, line.width, line.height, ARC, ARC));
		}
		if (target.getLine() == MenuTarget.Line.FAIRY_CODE)
		{
			dials.shapes(target.getLineKey()).forEach(shape -> draw(graphics, shape));
		}
		return null;
	}

	private static void draw(Graphics2D graphics, Shape shape)
	{
		graphics.setColor(Markers.FILL);
		graphics.fill(shape);
		graphics.setColor(Markers.COLOUR);
		graphics.draw(shape);
	}
}
