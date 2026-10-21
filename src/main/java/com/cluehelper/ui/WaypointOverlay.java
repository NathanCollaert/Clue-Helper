package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.SubStep;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.link.ClueLink;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.SceneDoors;
import com.cluehelper.route.ScenePath;
import com.cluehelper.route.WalkData;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.route.SceneBfs;
import com.cluehelper.route.WalkTable;
import com.cluehelper.step.Step;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Waypoints are never core's target, so the path ends where core's marker takes over. */
public class WaypointOverlay extends Overlay
{
	/** Near a stop where only an NPC (a banker) is outlined, the outline is guidance enough. */
	private static final int NEAR_OUTLINED_NPC_TILES = 6;
	private static final float PATH_STROKE = 2f;
	private static final int[] CONVERSATION = {InterfaceID.ChatLeft.UNIVERSE, InterfaceID.ChatRight.UNIVERSE, InterfaceID.Chatmenu.UNIVERSE};

	private final Client client;
	private final StepGuide guide;
	private final RoutePlanner routes;
	private final ClueHelperConfig config;
	private final Highlighter highlighter;
	private final ClueLink clueLink;

	private SceneBfs.Result pathScene;
	private WorldPoint pathGoal;
	private List<WorldPoint> path = List.of();
	private final SceneDoors doors;
	private WorldPoint instanceTile;
	private SceneBfs.Result instanceWalk;
	private Map<TileObject, String> pathObjects = Map.of();
	private int pathObjectsTick = -1;

	@Inject
	WaypointOverlay(Client client, StepGuide guide, RoutePlanner routes, ClueHelperConfig config, Highlighter highlighter,
		ClueLink clueLink, SceneDoors doors)
	{
		this.clueLink = clueLink;
		this.doors = doors;
		this.client = client;
		this.guide = guide;
		this.routes = routes;
		this.config = config;
		this.highlighter = highlighter;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		SubStep current = guide.getCurrent();
		WorldView view = client.getTopLevelWorldView();
		if (inConversation())
		{
			return null;
		}
		WorldPoint waypoint = current == null ? null : current.getWaypoint();
		SceneBfs.Result scene = planScene();
		if (waypoint == null && current != null && current.getKind() == SubStep.Kind.WALK_TO_CLUE && current.getGoal() != null)
		{
			// Core marks the clue itself; only the way there is ours.
			if (config.pathLine() && scene != null && view != null)
			{
				drawPath(graphics, view, scene, current.getGoal());
			}
			return null;
		}
		if (waypoint == null && current != null && current.getKind() == SubStep.Kind.ACT && current.getStage() == null
			&& guide.getHighlightObjects().isEmpty())
		{
			WorldPoint act = actTarget(view);
			if (act != null && config.pathLine() && scene != null && view != null)
			{
				drawPath(graphics, view, scene, act);
			}
			return null;
		}
		if (!walkableWaypoint(waypoint, view) && view != null && !guide.getHighlightObjects().isEmpty())
		{
			drawPathToObject(graphics, view);
			return null;
		}
		if (waypoint == null || view == null)
		{
			return null;
		}
		TileObject object = outlined() ? cheapestObject(view, scene) : null;
		NPC approached = object == null && approachOutlined() ? nearestNpc() : null;
		if (approached != null)
		{
			drawApproach(graphics, view, scene, approached);
			return null;
		}
		WorldPoint goal = object == null ? waypoint : beside(view, scene, object);
		if (object == null && besideOutlinedNpc(waypoint) || object != null && standingOn(goal))
		{
			return null;
		}
		if (config.pathLine() && scene != null)
		{
			drawPath(graphics, view, scene, goal);
		}
		if (!outlined() && waypoint.getPlane() == view.getPlane())
		{
			drawTile(graphics, view, waypoint);
		}
		return null;
	}

	private void drawTile(Graphics2D graphics, WorldView view, WorldPoint waypoint)
	{
		LocalPoint local = LocalPoint.fromWorld(view, waypoint);
		Polygon tile = local == null ? null : Perspective.getCanvasTilePoly(client, local);
		if (tile != null)
		{
			highlighter.tile(graphics, tile);
		}
	}

	/** Where the step is done: its NPC, else its nearest target tile; null when the player is already there. */
	private WorldPoint actTarget(WorldView view)
	{
		Step step = clueLink.getCurrentStep();
		Player local = client.getLocalPlayer();
		if (step == null || local == null || view == null)
		{
			return null;
		}
		WorldPoint at = local.getWorldLocation();
		WorldPoint target = nearestNamedNpc(view, step.getNpcs(), at);
		if (target == null && step.getTargets() != null)
		{
			target = step.getTargets().stream().min(Comparator.comparingInt(t -> t.distanceTo2D(at))).orElse(null);
		}
		return target == null || target.distanceTo2D(at) <= 1 ? null : target;
	}

	private static WorldPoint nearestNamedNpc(WorldView view, List<String> names, WorldPoint at)
	{
		if (names == null || names.isEmpty())
		{
			return null;
		}
		WorldPoint best = null;
		for (NPC npc : view.npcs())
		{
			WorldPoint tile = npc.getWorldLocation();
			boolean named = npc.getName() != null && names.stream().anyMatch(name -> name.equalsIgnoreCase(npc.getName()));
			if (named && (best == null || tile.distanceTo2D(at) < best.distanceTo2D(at)))
			{
				best = tile;
			}
		}
		return best;
	}

	/** Once the player is talking, the way there is done; the chat options carry the step. */
	private boolean inConversation()
	{
		for (int dialogue : CONVERSATION)
		{
			Widget widget = client.getWidget(dialogue);
			if (widget != null && !widget.isHidden())
			{
				return true;
			}
		}
		return false;
	}

	private boolean besideOutlinedNpc(WorldPoint waypoint)
	{
		Player local = client.getLocalPlayer();
		return outlined() && local != null && local.getWorldLocation().distanceTo2D(waypoint) <= NEAR_OUTLINED_NPC_TILES;
	}

	/** The step's bank, portal, ladder or NPC is outlined, which marks the spot better than its tile. */
	private boolean outlined()
	{
		return !(guide.getHighlightObjects().isEmpty() && guide.getHighlightNpcs().isEmpty());
	}

	private boolean approachOutlined()
	{
		return guide.isApproach() && guide.getHighlightObjects().isEmpty() && !guide.getHighlightNpcs().isEmpty();
	}

	/** Renu and other NPCs that teleport: the path leads to the NPC. */
	private void drawApproach(Graphics2D graphics, WorldView view, SceneBfs.Result scene, NPC npc)
	{
		Player local = client.getLocalPlayer();
		WorldPoint at = npc.getWorldLocation();
		if (local == null || local.getWorldLocation().distanceTo2D(at) <= 1)
		{
			return;
		}
		if (config.pathLine() && scene != null)
		{
			drawPath(graphics, view, scene, at);
		}
	}

	private NPC nearestNpc()
	{
		Player local = client.getLocalPlayer();
		NPC best = null;
		for (NPC npc : guide.getHighlightNpcs())
		{
			if (local != null && (best == null
				|| npc.getWorldLocation().distanceTo2D(local.getWorldLocation()) < best.getWorldLocation().distanceTo2D(local.getWorldLocation())))
			{
				best = npc;
			}
		}
		return best;
	}

	private List<WorldPoint> ahead(List<WorldPoint> path)
	{
		Player local = client.getLocalPlayer();
		if (local == null || path.isEmpty())
		{
			return path;
		}
		WorldPoint at = local.getWorldLocation();
		int nearest = 0;
		int nearestDistance = Integer.MAX_VALUE;
		for (int i = 0; i < path.size(); i++)
		{
			int distance = path.get(i).getPlane() == at.getPlane() ? path.get(i).distanceTo2D(at) : Integer.MAX_VALUE;
			if (distance <= nearestDistance)
			{
				nearest = i;
				nearestDistance = distance;
			}
		}
		return path.subList(nearest, path.size());
	}

	/** The walk table's tiles for a house are not the instance's own, so in a house the object leads the way. */
	private static boolean walkableWaypoint(WorldPoint waypoint, WorldView view)
	{
		return waypoint != null && view != null && !view.isInstance();
	}

	/** A nexus, altar or other object the step uses, where the step has no tile in the loaded scene to walk to. */
	private void drawPathToObject(Graphics2D graphics, WorldView view)
	{
		SceneBfs.Result scene = view.isInstance() ? instanceScene(view) : planScene();
		if (!config.pathLine() || scene == null)
		{
			return;
		}
		TileObject object = cheapestObject(view, scene);
		if (object != null && !besidePlayer(view, object))
		{
			drawPath(graphics, view, scene, beside(view, scene, object));
		}
	}

	private SceneBfs.Result instanceScene(WorldView view)
	{
		Player local = client.getLocalPlayer();
		WorldPoint at = local == null ? null : local.getWorldLocation();
		if (at != null && !at.equals(instanceTile))
		{
			SceneBfs.Scene scene = doors.open(client, view, SceneBfs.Scene.captureLoaded(view));
			instanceTile = at;
			instanceWalk = scene == null ? null : SceneBfs.run(scene, at, null, null);
		}
		return instanceWalk;
	}

	private boolean besidePlayer(WorldView view, TileObject object)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return false;
		}
		WorldPoint at = local.getWorldLocation();
		WorldPoint southWest = object.getWorldLocation();
		int sizeX = 1;
		int sizeY = 1;
		if (object instanceof GameObject)
		{
			GameObject game = (GameObject) object;
			Point min = game.getSceneMinLocation();
			southWest = WorldPoint.fromScene(view, min.getX(), min.getY(), game.getPlane());
			sizeX = game.sizeX();
			sizeY = game.sizeY();
		}
		return at.getPlane() == southWest.getPlane()
			&& at.getX() >= southWest.getX() - 1 && at.getX() <= southWest.getX() + sizeX
			&& at.getY() >= southWest.getY() - 1 && at.getY() <= southWest.getY() + sizeY;
	}

	/** The game walks to the nearest side of the booth or chest clicked. */
	private TileObject cheapestObject(WorldView view, SceneBfs.Result scene)
	{
		TileObject best = null;
		int bestCost = SceneBfs.UNREACHED;
		for (TileObject object : guide.getHighlightObjects())
		{
			int cost = scene == null ? SceneBfs.UNREACHED : scene.cost(beside(view, scene, object));
			if (best == null || cost < bestCost)
			{
				best = object;
				bestCost = cost;
			}
		}
		return best;
	}

	private boolean standingOn(WorldPoint tile)
	{
		Player local = client.getLocalPlayer();
		return local != null && local.getWorldLocation().equals(tile);
	}

	private static WorldPoint beside(WorldView view, SceneBfs.Result scene, TileObject object)
	{
		if (scene == null || !(object instanceof GameObject))
		{
			return object.getWorldLocation();
		}
		GameObject game = (GameObject) object;
		Point min = game.getSceneMinLocation();
		WorldPoint southWest = WorldPoint.fromScene(view, min.getX(), min.getY(), game.getPlane());
		WorldPoint side = scene.cheapestBeside(southWest, game.sizeX(), game.sizeY());
		return side != null ? side : object.getWorldLocation();
	}

	private SceneBfs.Result planScene()
	{
		PlanStart start = routes.getStart();
		return start == null ? null : start.getScene();
	}

	private void drawPath(Graphics2D graphics, WorldView view, SceneBfs.Result scene, WorldPoint waypoint)
	{
		WalkData.Loaded walk = routes.getWalk();
		WalkTable table = walk == null || view.isInstance() ? null : walk.getTable();
		boolean moved = scene != pathScene || !waypoint.equals(pathGoal);
		if (moved)
		{
			pathScene = scene;
			pathGoal = waypoint;
			path = ScenePath.toward(scene, table, waypoint);
		}
		List<WorldPoint> ahead = ahead(path);
		if (moved || pathObjectsTick != client.getTickCount())
		{
			pathObjectsTick = client.getTickCount();
			pathObjects = PathObjects.along(client, view, table, scene, doors, ahead);
		}
		pathObjects.forEach((object, option) -> highlighter.object(graphics, object, guide.isLeftClick(object, option)));
		graphics.setColor(Markers.COLOUR);
		graphics.setStroke(new BasicStroke(PATH_STROKE));
		Point previous = null;
		for (WorldPoint tile : ahead)
		{
			LocalPoint local = tile.getPlane() == view.getPlane() ? LocalPoint.fromWorld(view, tile) : null;
			Point at = local == null ? null : Perspective.localToCanvas(client, local, view.getPlane());
			if (previous != null && at != null)
			{
				graphics.drawLine(previous.getX(), previous.getY(), at.getX(), at.getY());
			}
			previous = at;
		}
	}
}
