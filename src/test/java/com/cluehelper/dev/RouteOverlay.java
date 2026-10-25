package com.cluehelper.dev;

import com.cluehelper.route.OriginClass;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.route.WalkCosts;
import com.cluehelper.route.WalkData;
import com.cluehelper.route.WalkTable;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

final class RouteOverlay extends Overlay
{
	private static final Map<Integer, Color> KIND_COLOURS = Map.of(
		WalkTable.KIND_TARGET, Color.MAGENTA,
		WalkTable.KIND_BANK, Color.YELLOW,
		WalkTable.KIND_ORIGIN, Color.CYAN,
		WalkTable.KIND_CHANGE, Color.ORANGE,
		WalkTable.KIND_LANDING, Color.GREEN);
	private static final int[] KIND_ORDER = {WalkTable.KIND_TARGET, WalkTable.KIND_BANK, WalkTable.KIND_ORIGIN,
		WalkTable.KIND_CHANGE, WalkTable.KIND_LANDING};
	private static final Point STATUS_AT = new Point(10, 40);

	private final Client client;
	private final RoutePlanner routes;
	private final WalkData walkData;

	RouteOverlay(Client client, RoutePlanner routes, WalkData walkData)
	{
		this.client = client;
		this.routes = routes;
		this.walkData = walkData;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		WalkData.Loaded data = walkData.get().getNow(null);
		PlanStart start = routes.getStart();
		if (data == null || start == null || client.getLocalPlayer() == null)
		{
			return null;
		}
		WalkTable table = data.getTable();
		WalkCosts walk = start.getWalk();
		int plane = client.getTopLevelWorldView().getPlane();
		for (int node = 0; node < table.nodeCount(); node++)
		{
			WorldPoint tile = table.placed(node) != null ? table.placed(node) : table.tile(node);
			LocalPoint local = tile.getPlane() == plane ? LocalPoint.fromWorld(client.getTopLevelWorldView(), tile) : null;
			if (local != null)
			{
				drawNode(graphics, local, node + " " + walk(walk, tile), colour(table, node));
			}
		}
		String origin = start.getTile() == null ? "-" : OriginClass.of(start.getTile(), data.getNoTeleport()).name();
		OverlayUtil.renderTextLocation(graphics, STATUS_AT, "start " + start.getKind() + ", origin " + origin
			+ ", nearest node " + routes.getNearestNode(), Color.WHITE);
		return null;
	}

	private void drawNode(Graphics2D graphics, LocalPoint local, String text, Color colour)
	{
		Polygon poly = Perspective.getCanvasTilePoly(client, local);
		if (poly != null)
		{
			OverlayUtil.renderPolygon(graphics, poly, colour);
		}
		Point at = Perspective.getCanvasTextLocation(client, graphics, local, text, 0);
		if (at != null)
		{
			OverlayUtil.renderTextLocation(graphics, at, text, colour);
		}
	}

	private static String walk(WalkCosts walk, WorldPoint tile)
	{
		int cost = walk == null ? WalkCosts.UNREACHED : walk.cost(tile);
		return cost == WalkCosts.UNREACHED ? "-" : String.valueOf(cost);
	}

	private static Color colour(WalkTable table, int node)
	{
		for (int kind : KIND_ORDER)
		{
			if (table.is(node, kind))
			{
				return KIND_COLOURS.get(kind);
			}
		}
		return Color.GRAY;
	}
}
