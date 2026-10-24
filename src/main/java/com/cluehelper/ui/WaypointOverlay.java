package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.MenuTarget;
import com.cluehelper.guide.SubStep;
import com.cluehelper.guide.SubSteps;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.link.ClueLink;
import com.cluehelper.route.Leg;
import com.cluehelper.route.PlanStart;
import com.cluehelper.route.Route;
import com.cluehelper.route.SceneDoors;
import com.cluehelper.route.ScenePath;
import com.cluehelper.route.WalkData;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.route.SceneBfs;
import com.cluehelper.route.WalkTable;
import com.cluehelper.step.Stage;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepIndex;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import javax.inject.Inject;
import lombok.Value;
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
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Waypoints are never core's target, so the path ends where core's marker takes over. */
public class WaypointOverlay extends Overlay
{
	private static final int NOT_CROSSING = -1;
	/** How far from core's tile a wandering NPC is still the step's. */
	private static final int WANDER_TILES = 15;
	private static final int NO_ANIMATION = -1;
	/** A crossing has started once the player has had a tick to step in. */
	private static final int CROSSING_START_TICKS = 2;
	private static final int CROSSING_TICKS = 10;
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
	private WorldPoint pathVia;
	private final ScenePath scenePath = new ScenePath();
	private int crossingFrom = NOT_CROSSING;
	private List<WorldPoint> path = List.of();
	private final SceneDoors doors;
	private WorldPoint instanceTile;
	private SceneBfs.Result instanceWalk;
	private Map<TileObject, String> pathObjects = Map.of();
	private int pathObjectsTick = -1;

	private final PathObjects paths;

	@Inject
	WaypointOverlay(Client client, StepGuide guide, RoutePlanner routes, ClueHelperConfig config, Highlighter highlighter,
		ClueLink clueLink, SceneDoors doors, PathObjects paths)
	{
		this.clueLink = clueLink;
		this.paths = paths;
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
		if (!config.pathLine() || inConversation())
		{
			return null;
		}
		WorldView view = client.getTopLevelWorldView();
		expireCrossing();
		if (view == null)
		{
			return null;
		}
		if (isCrossing())
		{
			drawLine(graphics, view, ahead(path));
			return null;
		}
		guide.getPickUpTiles().stream().filter(tile -> tile.getPlane() == view.getPlane()).forEach(tile -> drawTile(graphics, view, tile));
		SceneBfs.Result scene = planScene();
		Goal goal = goal(view, scene, guide.getCurrent());
		if (goal == null)
		{
			return null;
		}
		switch (goal.getKind())
		{
			case TO_OBJECT:
				drawPathToObject(graphics, view);
				break;
			case APPROACH:
				drawApproach(graphics, view, scene, goal.getNpc());
				break;
			case PATH:
				if (scene != null)
				{
					drawPath(graphics, view, scene, goal.getTile());
				}
				if (goal.isMarkTile())
				{
					drawTile(graphics, view, goal.getTile());
				}
				break;
			default:
				throw new IllegalArgumentException("unhandled goal " + goal.getKind());
		}
		return null;
	}

	/** Where the line leads for the current sub-step; null when nothing is drawn. */
	private Goal goal(WorldView view, SceneBfs.Result scene, SubStep current)
	{
		WorldPoint waypoint = current == null ? null : current.getWaypoint();
		if (waypoint == null && current != null && current.getKind() == SubStep.Kind.WALK_TO_CLUE && current.getGoal() != null)
		{
			return pathToClue(view, current);
		}
		if (waypoint == null && current != null && current.isPreparation())
		{
			return null;
		}
		if (waypoint == null && current != null && isActWithoutPlace(current))
		{
			WorldPoint act = actTarget(view, current.getTab() == StepGuide.Tab.EMOTES ? SubSteps.ARRIVE_TILES : 1);
			return act == null ? null : Goal.path(act, false);
		}
		if (!walkableWaypoint(waypoint, view) && !guide.getHighlightObjects().isEmpty())
		{
			return Goal.toObject();
		}
		NPC target = waypoint == null && current != null && current.getKind() == SubStep.Kind.ACT ? nearestNpc() : null;
		if (target != null && walkableWaypoint(target.getWorldLocation(), view))
		{
			return Goal.approach(target);
		}
		if (waypoint == null)
		{
			return null;
		}
		TileObject object = outlined() ? cheapestObject(view, scene) : null;
		NPC approached = object == null && approachOutlined() ? nearestNpc() : null;
		if (approached != null)
		{
			return Goal.approach(approached);
		}
		WorldPoint goal = object == null ? waypoint : beside(view, scene, object);
		if (object == null && besideOutlinedNpc(waypoint) || object != null && standingOn(goal))
		{
			return null;
		}
		return Goal.path(goal, !outlined() && waypoint.getPlane() == view.getPlane());
	}

	/** Core marks the clue itself; only the way there is ours. */
	private Goal pathToClue(WorldView view, SubStep current)
	{
		WorldPoint goal = wandererOr(view, current.getGoal());
		return goal == null ? null : Goal.path(goal, false);
	}

	/** The step's own act, or one part of a three-step, with nothing outlined to walk to and no item to click. */
	private boolean isActWithoutPlace(SubStep current)
	{
		boolean stepOrPartAct = current.getStage() == null || current.getStage().getKind() == Stage.Kind.PART;
		return current.getKind() == SubStep.Kind.ACT && stepOrPartAct && current.getActItemNamed() == null
			&& guide.getHighlightObjects().isEmpty();
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

	/** Where the step is done: its NPC, else its nearest target tile; null within {@code here} tiles of it. */
	private WorldPoint actTarget(WorldView view, int here)
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
		return target == null || target.distanceTo2D(at) <= here ? null : target;
	}

	/**
	 * The step's NPC where it wanders rather than core's tile for it, once in view: anywhere in its hand-data range
	 * (Wingstone across the desert), else near the tile (Lovada about her house). Inside a range with the NPC not in
	 * view, null: the range's tiles only lead the route there.
	 */
	private WorldPoint wandererOr(WorldView view, WorldPoint goal)
	{
		Step step = clueLink.getCurrentStep();
		StepIndex.Wander range = step == null ? null : step.getData().getWander();
		WorldPoint npc = step == null ? null : nearestNamedNpc(view, step.getNpcs(), goal);
		boolean npcThere = npc != null && npc.getPlane() == goal.getPlane()
			&& (range != null ? range.contains(npc) : npc.distanceTo2D(goal) <= WANDER_TILES);
		if (npcThere)
		{
			return npc;
		}
		Player local = client.getLocalPlayer();
		boolean looking = range != null && local != null && range.contains(local.getWorldLocation());
		return looking ? null : goal;
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
		SubStep current = guide.getCurrent();
		boolean boarding = current != null && current.getKind() == SubStep.Kind.TELEPORT && current.getRow() != null
			&& current.getRow().isLocated();
		return (guide.isApproach() || boarding || guide.isExit()) && guide.getHighlightObjects().isEmpty()
			&& !guide.getHighlightNpcs().isEmpty();
	}

	/** Renu, NPCs that teleport and NPCs that are the way in or out (the Guardian mummy): the path leads to the NPC. */
	private void drawApproach(Graphics2D graphics, WorldView view, SceneBfs.Result scene, NPC npc)
	{
		Player local = client.getLocalPlayer();
		WorldPoint at = npc.getWorldLocation();
		if (local == null || local.getWorldLocation().distanceTo2D(at) <= 1)
		{
			return;
		}
		if (scene != null)
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
		if (scene == null)
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
			SceneBfs.Scene scene = doors.withDoorsOpen(view, SceneBfs.Scene.captureLoaded(view), null);
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

	/** The followed route when this line is its first walk's: that walk ends at the goal. */
	private Route plannedStart(WorldPoint goal)
	{
		Route route = routes.getCommitted();
		Leg first = route == null || route.getLegs().isEmpty() ? null : route.getLegs().get(0);
		boolean thisWalk = first != null && first.getKind() == Leg.Kind.WALK && first.getTo() != null && first.getTo().distanceTo2D(goal) <= 1;
		return thisWalk ? route : null;
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
		Route planned = plannedStart(waypoint);
		WorldPoint via = planned == null ? null : planned.getStartVia();
		boolean moved = scene != pathScene || !waypoint.equals(pathGoal) || !Objects.equals(via, pathVia);
		if (moved)
		{
			pathScene = scene;
			pathGoal = waypoint;
			pathVia = via;
			path = scenePath.line(scene, table, waypoint, via, planned == null ? null : planned.getStartJoinedFrom());
		}
		List<WorldPoint> ahead = ahead(path);
		if (moved || pathObjectsTick != client.getTickCount())
		{
			pathObjectsTick = client.getTickCount();
			pathObjects = paths.along(view, table, scene, ahead);
		}
		drawLine(graphics, view, toUseFirst(view, scene, ahead));
	}

	/** A row object on the way that opens only once another is used (Meiyerditch's barricade): the line leads there first. */
	private List<WorldPoint> toUseFirst(WorldView view, SceneBfs.Result scene, List<WorldPoint> ahead)
	{
		TileObject first = pathObjects.keySet().stream().filter(object -> PathObjects.isUsedFirst(object.getId())).findFirst().orElse(null);
		WorldPoint stand = first == null ? null : beside(view, scene, first);
		if (stand == null || standingOn(stand))
		{
			return ahead;
		}
		List<WorldPoint> there = scene.pathTo(stand);
		return there.isEmpty() ? ahead : there;
	}

	/**
	 * Client thread. Taking a gate, cart or shortcut the line leads through: the player stands inside it or is carried
	 * across, where the scene's walk starts from a tile it can't leave, so the line stands until they are through. Through
	 * it, they may have gone back the way they came: the line picks its walk again.
	 */
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (pathObjects.keySet().stream().anyMatch(object -> object.getId() == event.getId()))
		{
			crossingFrom = client.getTickCount();
		}
	}

	/** Client thread. Each object the line leads through, with its option, for the right-click menu to colour. */
	public List<MenuTarget> pathObjectTargets()
	{
		List<MenuTarget> targets = new java.util.ArrayList<>();
		pathObjects.forEach((object, option) -> targets.add(MenuTarget.builder().objectIds(Set.of(object.getId()))
			.path(List.of(option)).build()));
		return targets;
	}

	/** A crossing ends once its time is up, or once the player has had a tick to step in and stands idle: the line then picks its walk again. */
	private void expireCrossing()
	{
		if (crossingFrom == NOT_CROSSING)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		int since = client.getTickCount() - crossingFrom;
		boolean idle = local != null && local.getAnimation() == NO_ANIMATION && local.getPoseAnimation() == local.getIdlePoseAnimation();
		if (since > CROSSING_TICKS || since >= CROSSING_START_TICKS && idle)
		{
			crossingFrom = NOT_CROSSING;
			scenePath.forget();
			pathScene = null;
		}
	}

	private boolean isCrossing()
	{
		return crossingFrom != NOT_CROSSING;
	}

	private void drawLine(Graphics2D graphics, WorldView view, List<WorldPoint> ahead)
	{
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
	/** What the line leads to: a tile, an NPC to approach, or the outlined objects' nearest side. */
	@Value
	private static class Goal
	{
		enum Kind
		{
			PATH, APPROACH, TO_OBJECT
		}

		Kind kind;
		WorldPoint tile;
		NPC npc;
		/** The tile itself is marked, as nothing on it is outlined. */
		boolean markTile;

		static Goal path(WorldPoint tile, boolean markTile)
		{
			return new Goal(Kind.PATH, tile, null, markTile);
		}

		static Goal approach(NPC npc)
		{
			return new Goal(Kind.APPROACH, null, npc, false);
		}

		private static Goal toObject()
		{
			return new Goal(Kind.TO_OBJECT, null, null, false);
		}
	}
}
