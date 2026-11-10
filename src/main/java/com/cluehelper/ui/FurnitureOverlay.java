package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.link.ClueLink;
import com.cluehelper.step.Step;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.client.ui.overlay.*;

public class FurnitureOverlay extends Overlay
{
	@Inject
	private StepGuide guide;
	@Inject
	private ClueHelperConfig config;
	@Inject
	private Highlighter highlighter;
	@Inject
	private LineTargets targets;
	@Inject
	private Client client;
	@Inject
	private ClueLink clueLink;

	FurnitureOverlay()
	{
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
		if (npc != null && !isMarkedByCore(npc))
		{
			highlighter.npc(graphics, npc, guide.getFillNpcs().contains(npc));
		}
		return null;
	}

	/** Core's clue scroll plugin already outlines the NPCs its clue names. */
	private boolean isMarkedByCore(NPC npc)
	{
		Step step = clueLink.getCurrentStep();
		return step != null && step.getNpcs() != null && WaypointOverlay.isNamed(npc, step.getNpcs());
	}
}
