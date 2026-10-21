package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import javax.inject.Inject;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

public class FurnitureOverlay extends Overlay
{
	/** Wide enough to show on the nexus, which is cyan itself. */
	private static final int OUTLINE_WIDTH = 4;
	private static final int FEATHER = 4;

	private final StepGuide guide;
	private final ClueHelperConfig config;
	private final ModelOutlineRenderer outlines;

	@Inject
	FurnitureOverlay(StepGuide guide, ClueHelperConfig config, ModelOutlineRenderer outlines)
	{
		this.guide = guide;
		this.config = config;
		this.outlines = outlines;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.highlightMenus())
		{
			return null;
		}
		graphics.setColor(Markers.ONE_CLICK_FILL);
		for (TileObject object : guide.getFillObjects())
		{
			Shape clickbox = object.getClickbox();
			if (clickbox != null)
			{
				graphics.fill(clickbox);
			}
		}
		for (TileObject object : guide.getHighlightObjects())
		{
			outlines.drawOutline(object, OUTLINE_WIDTH, Markers.COLOUR, FEATHER);
		}
		for (NPC banker : guide.getHighlightNpcs())
		{
			outlines.drawOutline(banker, OUTLINE_WIDTH, Markers.COLOUR, FEATHER);
		}
		return null;
	}
}
