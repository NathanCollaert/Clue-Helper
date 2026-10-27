package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.client.ui.overlay.*;

public class FurnitureOverlay extends Overlay
{
	private final StepGuide guide;
	private final ClueHelperConfig config;
	private final Highlighter highlighter;
	private final LineTargets targets;
	private final Client client;

	@Inject
	FurnitureOverlay(Client client, StepGuide guide, ClueHelperConfig config, Highlighter highlighter, LineTargets targets)
	{
		this.guide = guide;
		this.config = config;
		this.highlighter = highlighter;
		this.targets = targets;
		this.client = client;
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
		WorldView view = client.getTopLevelWorldView();
		TileObject object = view == null ? null : targets.object(view);
		if (object != null)
		{
			highlighter.object(graphics, object, guide.getFillObjects().contains(object));
		}
		NPC npc = targets.npc();
		if (npc != null)
		{
			highlighter.npc(graphics, npc, guide.getFillNpcs().contains(npc));
		}
		return null;
	}
}
