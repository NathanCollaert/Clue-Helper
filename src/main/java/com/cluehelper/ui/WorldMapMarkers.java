package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

@Singleton
public class WorldMapMarkers
{
	private static final String TOOLTIP_PREFIX = "Clue Helper: ";

	private final WorldMapPointManager manager;
	private final StepGuide guide;
	private final ClueHelperConfig config;
	private Map<WorldPoint, String> shown = Map.of();

	@Inject
	WorldMapMarkers(WorldMapPointManager manager, StepGuide guide, ClueHelperConfig config)
	{
		this.manager = manager;
		this.guide = guide;
		this.config = config;
	}

	/** Call on the client thread after the guide or the config changes. */
	public void refresh()
	{
		Map<WorldPoint, String> places = config.worldMapMarker() ? guide.getMapWaypoints() : Map.of();
		if (places.equals(shown))
		{
			return;
		}
		manager.removeIf(RoutePoint.class::isInstance);
		places.forEach((tile, label) -> manager.add(new RoutePoint(tile, TOOLTIP_PREFIX + label)));
		shown = places;
	}

	public void clear()
	{
		manager.removeIf(RoutePoint.class::isInstance);
		shown = Map.of();
	}

	/** Our own point class, so removing ours never touches anyone else's. */
	private static final class RoutePoint extends WorldMapPoint
	{
		RoutePoint(WorldPoint tile, String tooltip)
		{
			super(tile, Markers.DIAMOND_IMAGE);
			setTooltip(tooltip);
			setName(tooltip);
		}
	}
}
