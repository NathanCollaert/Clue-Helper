package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

public class FurnitureOverlay extends Overlay
{
	private final StepGuide guide;
	private final ClueHelperConfig config;
	private final Highlighter highlighter;

	@Inject
	FurnitureOverlay(StepGuide guide, ClueHelperConfig config, Highlighter highlighter)
	{
		this.guide = guide;
		this.config = config;
		this.highlighter = highlighter;
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
		List<TileObject> leftClick = guide.getFillObjects();
		for (TileObject object : guide.getHighlightObjects())
		{
			highlighter.object(graphics, object, leftClick.contains(object));
		}
		List<NPC> leftClickNpcs = guide.getFillNpcs();
		for (NPC npc : guide.getHighlightNpcs())
		{
			highlighter.npc(graphics, npc, leftClickNpcs.contains(npc));
		}
		return null;
	}
}
