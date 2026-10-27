package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.LeftClicks;
import com.cluehelper.guide.MenuTarget;
import com.cluehelper.guide.SubStep;
import com.cluehelper.guide.SubSteps;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.link.ClueLink;
import com.cluehelper.route.Ground;
import com.cluehelper.route.WalkGoal;
import com.cluehelper.route.WalkData;
import com.cluehelper.route.WalkTable;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.route.WalkCosts;
import com.cluehelper.route.WorldWalk;
import com.cluehelper.step.Stage;
import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepIndex;
import com.cluehelper.transport.Category;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import lombok.Value;
import net.runelite.api.Client;
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
	private static final int OBELISK_PLATFORM_TILES = 3;
	private static final float PATH_STROKE = 2f;
	/** A climb's far end can stand a few tiles off the walk data's landing tile (the Observatory's rocks). */
	private static final int ROW_ENDS_MARGIN_TILES = 3;
	/** A longer jump (a cave entrance into another region) crosses nothing between its ends: only its start is looked at. */
	private static final int CROSSING_JUMP_TILES = 20;
	private static final int[] CONVERSATION = {InterfaceID.ChatLeft.UNIVERSE, InterfaceID.ChatRight.UNIVERSE, InterfaceID.Chatmenu.UNIVERSE};
	private static final String NO_STEP = "";

	private final Client client;
	private final StepGuide guide;
	private final RoutePlanner routes;
	private final ClueHelperConfig config;
	private final Highlighter highlighter;
	private final ClueLink clueLink;

	private int crossingFrom = NOT_CROSSING;
	private List<WorldPoint> drawnWalk = List.of();
	private List<WorldPoint> line = List.of();
	private Map<TileObject, String> pathObjects = Map.of();
	private int lineTick = -1;
	/** The step the open conversation began on; null with no conversation open. */
	private String conversationStepKey;

	private final PathObjects paths;
	private final WalkLine walkLine;
	private final LineTargets targets;
	private final WorldWalk walker;
	private final LeftClicks leftClicks;

	@Inject
	WaypointOverlay(Client client, StepGuide guide, RoutePlanner routes, ClueHelperConfig config, Highlighter highlighter,
		ClueLink clueLink, PathObjects paths, WalkLine walkLine, LineTargets targets, WorldWalk walker, LeftClicks leftClicks)
	{
		this.leftClicks = leftClicks;
		this.clueLink = clueLink;
		this.paths = paths;
		this.walkLine = walkLine;
		this.targets = targets;
		this.walker = walker;
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
		noteConversationStep();
		if (!config.pathLine() || isTalkingOnThisStep())
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
			if (lineTick != client.getTickCount())
			{
				lineTick = client.getTickCount();
				followAhead(view);
				line = walkLine.getAhead();
			}
			drawLine(graphics, view, line);
			return null;
		}
		guide.getPickUpTiles().stream().filter(tile -> tile.getPlane() == view.getPlane()).forEach(tile -> drawTile(graphics, view, tile));
		WalkCosts here = targets.planWalk();
		Goal goal = goal(view, here, guide.getCurrent());
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
				drawApproach(graphics, view, here, goal.getNpc());
				break;
			case PATH:
				if (here != null)
				{
					drawPath(graphics, view, here, goal.getWalk());
				}
				if (goal.isMarkTile())
				{
					drawTile(graphics, view, goal.getWalk().getSouthWest());
				}
				break;
			default:
				throw new IllegalArgumentException("unhandled goal " + goal.getKind());
		}
		return null;
	}

	/** Where the line leads for the current sub-step; null when nothing is drawn. */
	private Goal goal(WorldView view, WalkCosts here, SubStep current)
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
			WorldPoint act = actTarget(view, current, current.getTab() == StepGuide.Tab.EMOTES ? SubSteps.ARRIVE_TILES : 1);
			return act == null ? null : Goal.path(WalkGoal.tile(act), false);
		}
		if (!walkableWaypoint(waypoint, view) && !guide.getHighlightObjects().isEmpty())
		{
			return Goal.toObject();
		}
		NPC target = waypoint == null && current != null && current.getKind() == SubStep.Kind.ACT ? targets.npc() : null;
		if (target != null && walkableWaypoint(target.getWorldLocation(), view))
		{
			return Goal.approach(target);
		}
		if (waypoint == null)
		{
			return null;
		}
		if (isObeliskTeleport(current))
		{
			return Goal.path(WalkGoal.area(waypoint, OBELISK_PLATFORM_TILES, OBELISK_PLATFORM_TILES), false);
		}
		TileObject object = outlined() ? targets.object(view) : null;
		NPC approached = object == null && approachOutlined() ? targets.npc() : null;
		if (approached != null)
		{
			return Goal.approach(approached);
		}
		if (object == null && besideOutlinedNpc(waypoint) || object != null && besidePlayer(view, object))
		{
			return null;
		}
		WalkGoal goal = object == null ? WalkGoal.tile(waypoint) : LineTargets.objectGoal(view, object);
		return Goal.path(goal, !outlined() && !awaitsNpc() && waypoint.getPlane() == view.getPlane());
	}

	/** Core marks the clue itself; only the way there is ours. */
	private Goal pathToClue(WorldView view, SubStep current)
	{
		WorldPoint goal = wandererOr(view, current.getGoal());
		return goal == null ? null : Goal.path(WalkGoal.tile(goal), false);
	}

	/**
	 * The step's own act, one part of a three-step or the kill for a key, with nothing outlined to walk to and no item to
	 * click.
	 */
	private boolean isActWithoutPlace(SubStep current)
	{
		boolean stepOrPartAct = current.getStage() == null || current.getStage().getKind() == Stage.Kind.PART || isKillForKey(current);
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

	/**
	 * Where the act is done: its NPC, else its nearest tile; null within {@code here} tiles of it, and inside a
	 * wanderer's range with it out of view, where the player looks for it. The kill for a key leads to the NPC
	 * dropping the key, else the stage's marker. A hot/cold feel without a waypoint is done where the player stands:
	 * the step's tiles are only the places it might be felt from.
	 */
	private WorldPoint actTarget(WorldView view, SubStep current, int here)
	{
		Step step = clueLink.getCurrentStep();
		Player local = client.getLocalPlayer();
		if (step == null || local == null || view == null || ClueTypes.HOT_COLD.equals(step.getType()))
		{
			return null;
		}
		boolean killForKey = isKillForKey(current);
		WorldPoint at = local.getWorldLocation();
		WorldPoint target = killForKey ? nearestKeyHolder(view, step, current.getStage(), at) : nearestNamedNpc(view, step.getNpcsAtStep(), at);
		StepIndex.Wander range = step.getData().getWander();
		if (target == null && !killForKey && range != null && range.contains(at))
		{
			return null;
		}
		List<WorldPoint> tiles = killForKey ? current.getStage().getTiles() : step.getTargets();
		if (target == null && tiles != null)
		{
			target = tiles.stream().min(Comparator.comparingInt(t -> t.distanceTo2D(at))).orElse(null);
		}
		return target == null || target.distanceTo2D(at) <= here ? null : target;
	}

	private static boolean isKillForKey(SubStep current)
	{
		return current.getStage() != null && current.getStage().getKind() == Stage.Kind.KEY;
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
		WorldPoint npc = step == null ? null : nearestNamedNpc(view, step.getNpcsAtStep(), goal);
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

	/** The NPC dropping the key: the stage's own (the Mercenary Captain), else core's for the clue. */
	private static WorldPoint nearestKeyHolder(WorldView view, Step step, Stage kill, WorldPoint at)
	{
		return kill.getNpcIds().isEmpty() ? nearestNamedNpc(view, step.getNpcs(), at)
			: nearestNpc(view, npc -> kill.getNpcIds().contains(npc.getId()), at);
	}

	private static WorldPoint nearestNamedNpc(WorldView view, List<String> names, WorldPoint at)
	{
		if (names == null || names.isEmpty())
		{
			return null;
		}
		return nearestNpc(view, npc -> npc.getName() != null && names.stream().anyMatch(name -> name.equalsIgnoreCase(npc.getName())), at);
	}

	private static WorldPoint nearestNpc(WorldView view, Predicate<NPC> wanted, WorldPoint at)
	{
		WorldPoint best = null;
		for (NPC npc : view.npcs())
		{
			WorldPoint tile = npc.getWorldLocation();
			if (wanted.test(npc) && (best == null || tile.distanceTo2D(at) < best.distanceTo2D(at)))
			{
				best = tile;
			}
		}
		return best;
	}

	private void noteConversationStep()
	{
		if (!inConversation())
		{
			conversationStepKey = null;
		}
		else if (conversationStepKey == null)
		{
			conversationStepKey = currentStepKey();
		}
	}

	/**
	 * Once the player is talking, the way there is done and the chat options carry the step. A step the talk moved on
	 * to (the next clue handed over) gets its line while the chat is still open.
	 */
	private boolean isTalkingOnThisStep()
	{
		return conversationStepKey != null && conversationStepKey.equals(currentStepKey());
	}

	private String currentStepKey()
	{
		Step step = clueLink.getCurrentStep();
		return step == null || step.getKey() == null ? NO_STEP : step.getKey();
	}

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

	/** An NPC is the stop (Renu, a ship's crew) but isn't loaded yet: it gets the outline once in view, never its tile. */
	private boolean awaitsNpc()
	{
		SubStep current = guide.getCurrent();
		return current != null && !current.getNpcIds().isEmpty() || !guide.getMenuTarget().getNpcIds().isEmpty();
	}

	/** Its row's origin is the south-west corner of the platform, and the whole platform teleports. */
	private static boolean isObeliskTeleport(SubStep current)
	{
		return current.getKind() == SubStep.Kind.TELEPORT && current.getRow() != null
			&& current.getRow().getCategory() == Category.WILDERNESS_OBELISK;
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
	private void drawApproach(Graphics2D graphics, WorldView view, WalkCosts here, NPC npc)
	{
		Player local = client.getLocalPlayer();
		WorldPoint at = npc.getWorldLocation();
		if (local == null || local.getWorldLocation().distanceTo2D(at) <= 1)
		{
			return;
		}
		if (here != null)
		{
			drawPath(graphics, view, here, WalkGoal.tile(at));
		}
	}

	/** The walk table's tiles for a house are not the instance's own, so in a house the object leads the way. */
	private static boolean walkableWaypoint(WorldPoint waypoint, WorldView view)
	{
		return waypoint != null && view != null && !view.isInstance();
	}

	/** A nexus, altar or other object the step uses, where the step has no tile in the loaded scene to walk to. */
	private void drawPathToObject(Graphics2D graphics, WorldView view)
	{
		WalkCosts here = targets.walk(view);
		if (here == null)
		{
			return;
		}
		TileObject object = targets.object(view);
		if (object != null && !besidePlayer(view, object))
		{
			drawPath(graphics, view, here, LineTargets.objectGoal(view, object));
		}
	}

	private boolean besidePlayer(WorldView view, TileObject object)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return false;
		}
		WorldPoint at = local.getWorldLocation();
		WalkGoal goal = LineTargets.objectGoal(view, object);
		WorldPoint southWest = goal.getSouthWest();
		return at.getPlane() == southWest.getPlane()
			&& at.getX() >= southWest.getX() - 1 && at.getX() <= southWest.getX() + goal.getSizeX()
			&& at.getY() >= southWest.getY() - 1 && at.getY() <= southWest.getY() + goal.getSizeY();
	}

	private void drawPath(Graphics2D graphics, WorldView view, WalkCosts here, WalkGoal goal)
	{
		walkLine.follow(view, here, goal);
		List<WorldPoint> walk = walkLine.getWalk();
		if (walk != drawnWalk || lineTick != client.getTickCount())
		{
			drawnWalk = walk;
			lineTick = client.getTickCount();
			followAhead(view);
			line = toUseFirst(view, here, walkLine.getAhead());
		}
		drawLine(graphics, view, line);
	}

	/** The line moves on with the player, and the objects it still leads through are found again. */
	private void followAhead(WorldView view)
	{
		walkLine.advance();
		WalkData.Loaded data = routes.getWalk();
		pathObjects = paths.along(view, data == null || view.isInstance() ? null : data.getTable(), walkLine.getAhead());
	}

	/** A row object on the way that opens only once another is used (Meiyerditch's barricade): the line leads there first. */
	private List<WorldPoint> toUseFirst(WorldView view, WalkCosts here, List<WorldPoint> ahead)
	{
		TileObject first = pathObjects.keySet().stream().filter(object -> PathObjects.isUsedFirst(object.getId())).findFirst().orElse(null);
		Player local = client.getLocalPlayer();
		if (first == null || local == null || here.getScene() == null || besidePlayer(view, first))
		{
			return ahead;
		}
		List<WorldPoint> there = walker.path(Ground.sceneOnly(here.getScene()), local.getWorldLocation(), LineTargets.objectGoal(view, first));
		return there.isEmpty() ? ahead : there;
	}

	/**
	 * Client thread. Taking a gate, cart or shortcut the line leads through: the player stands inside it or is carried
	 * across, where a walk starts from a tile it can't leave, so the line moves on with them but isn't searched again
	 * until they are through. Through it, they may have gone back the way they came: the line picks its walk again.
	 */
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (pathObjects.keySet().stream().anyMatch(object -> object.getId() == event.getId()))
		{
			crossingFrom = client.getTickCount();
		}
	}

	/** Client thread. The tiles the line was last drawn through. */
	public List<WorldPoint> getLine()
	{
		return line;
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
			walkLine.forget();
		}
	}

	private boolean isCrossing()
	{
		return crossingFrom != NOT_CROSSING;
	}

	private void drawLine(Graphics2D graphics, WorldView view, List<WorldPoint> ahead)
	{
		pathObjects.forEach((object, option) -> highlighter.object(graphics, object, leftClicks.isLeftClick(object, option)));
		graphics.setColor(Markers.COLOUR);
		graphics.setStroke(new BasicStroke(PATH_STROKE));
		Point previous = null;
		WorldPoint previousTile = null;
		for (WorldPoint tile : ahead)
		{
			if (previous != null && PathObjects.isJump(previousTile, tile))
			{
				for (TileObject object : objectsCrossed(view, previousTile, tile))
				{
					Point over = Perspective.localToCanvas(client, object.getLocalLocation(), view.getPlane());
					previous = drawTo(graphics, previous, over);
				}
			}
			LocalPoint local = tile.getPlane() == view.getPlane() ? LocalPoint.fromWorld(view, tile) : null;
			Point at = local == null ? null : Perspective.localToCanvas(client, local, view.getPlane());
			previous = drawTo(graphics, previous, at);
			previousTile = tile;
		}
	}

	private static Point drawTo(Graphics2D graphics, Point from, Point to)
	{
		if (from != null && to != null)
		{
			graphics.drawLine(from.getX(), from.getY(), to.getX(), to.getY());
		}
		return to;
	}

	/**
	 * The objects a jump goes over, nearest first: the outlined one (a stile), the others named as it is on the way
	 * (every stepping stone, both ends of a pipe) and the objects of the jump's other rows (a climb's rocks at the top
	 * of its rope). The line leads over them, not straight from one end to the other. Across a short jump only those
	 * that each bring it nearer the far end count: the Kharazi Jungle's bushes stand in a wall, all one name and id,
	 * and would pull the line back and forth across it.
	 */
	private List<TileObject> objectsCrossed(WorldView view, WorldPoint from, WorldPoint jumpEnd)
	{
		boolean crossing = from.getPlane() == jumpEnd.getPlane() && from.distanceTo2D(jumpEnd) <= CROSSING_JUMP_TILES;
		WorldPoint to = crossing ? jumpEnd : from;
		Rectangle around = around(from, to, 1);
		Stream<TileObject> outlined = pathObjects.keySet().stream()
			.filter(object -> object.getPlane() == from.getPlane()
				&& around.contains(object.getWorldLocation().getX(), object.getWorldLocation().getY()))
			.flatMap(object -> paths.sameNamed(view, object, around).stream());
		WalkData.Loaded data = routes.getWalk();
		WalkTable table = data == null || view.isInstance() ? null : data.getTable();
		List<TileObject> rowEnds = paths.rowObjectsAround(view, table, from, jumpEnd, around(from, to, ROW_ENDS_MARGIN_TILES));
		List<TileObject> nearestFirst = Stream.concat(outlined, rowEnds.stream())
			.distinct()
			.sorted(Comparator.comparingInt(object -> squaredDistance(object.getWorldLocation(), from)))
			.collect(Collectors.toList());
		return crossing ? nearingEnd(nearestFirst, from, jumpEnd) : nearestFirst;
	}

	private static List<TileObject> nearingEnd(List<TileObject> nearestFirst, WorldPoint from, WorldPoint end)
	{
		List<TileObject> nearing = new ArrayList<>();
		int left = squaredDistance(from, end);
		for (TileObject object : nearestFirst)
		{
			int toEnd = squaredDistance(object.getWorldLocation(), end);
			if (toEnd < left)
			{
				nearing.add(object);
				left = toEnd;
			}
		}
		return nearing;
	}

	private static Rectangle around(WorldPoint from, WorldPoint to, int margin)
	{
		return new Rectangle(Math.min(from.getX(), to.getX()) - margin, Math.min(from.getY(), to.getY()) - margin,
			Math.abs(from.getX() - to.getX()) + 2 * margin + 1, Math.abs(from.getY() - to.getY()) + 2 * margin + 1);
	}

	private static int squaredDistance(WorldPoint a, WorldPoint b)
	{
		int dx = a.getX() - b.getX();
		int dy = a.getY() - b.getY();
		return dx * dx + dy * dy;
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
		WalkGoal walk;
		NPC npc;
		/** The goal's tile is marked, as nothing on it is outlined. */
		boolean markTile;

		static Goal path(WalkGoal walk, boolean markTile)
		{
			return new Goal(Kind.PATH, walk, null, markTile);
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
