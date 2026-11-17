package com.cluehelper.ui;

import com.cluehelper.*;
import javax.inject.*;
import net.runelite.client.plugins.cluescrolls.*;
import net.runelite.client.ui.overlay.*;

/**
 * Takes the Clue Scroll plugin's box out of the overlay manager while the Overlay setting hides it: Combined shows its
 * text in the Clue Helper box, Helper only shows none of it. Core adds its box back each time it starts, so this runs
 * again whenever core's state changes. Client thread.
 */
@Singleton
public class CoreBoxMerge
{
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private ClueHelperConfig config;
	@Inject
	private GuideOverlay guideOverlay;
	private ClueScrollOverlay hidden;

	/** @param activeCore the Clue Scroll plugin while it runs, which is when its box belongs in the overlay manager */
	public void apply(ClueScrollPlugin activeCore)
	{
		if (activeCore != null && config.guideBox().hidesCoreBox())
		{
			hide(activeCore);
		}
		else
		{
			giveBack(activeCore != null);
		}
	}

	private void hide(ClueScrollPlugin core)
	{
		overlayManager.removeIf(this::takeCoreBox);
		guideOverlay.mergeCoreBox(hidden, core);
	}

	private boolean takeCoreBox(Overlay overlay)
	{
		if (!(overlay instanceof ClueScrollOverlay))
		{
			return false;
		}
		hidden = (ClueScrollOverlay) overlay;
		return true;
	}

	/**
	 * Also called as the plugin stops, once the Clue Helper box has left the overlay manager. The box goes back only
	 * while core runs: a stopped core puts it back itself when it starts.
	 */
	public void giveBack(boolean coreActive)
	{
		guideOverlay.mergeCoreBox(null, null);
		if (hidden != null && coreActive)
		{
			overlayManager.add(hidden);
			hidden = null;
		}
	}
}
