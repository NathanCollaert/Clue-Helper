package com.cluehelper.ui;

import com.cluehelper.*;
import com.cluehelper.guide.*;
import com.cluehelper.hotcold.ProbeTarget;
import com.cluehelper.link.ClueLink;
import com.cluehelper.route.*;
import com.cluehelper.step.*;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.*;
import javax.inject.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.*;

/**
 * What the guide draws in the game scene: the path line and the tile it leads to, the edge of a wanderer's range or a
 * task's area, and the outlined objects and NPCs. Waypoints are never core's target, so the path ends where core's
 * marker takes over.
 */
@Singleton
public class SceneOverlay extends Overlay
{
	private static final int NOT_CROSSING = -1;
	/** Walked through, but the line steps over them into the crossing beside them: a trap that pushes the player back. */
	private static final Set<WorldPoint> SKIPPED_TILES = Tables.rows("line skips").stream().map(Tables.Row::ids)
		.map(at -> new WorldPoint(at[0], at[1], at[2])).collect(Collectors.toUnmodifiableSet());
	/** How far from core's tile a wandering NPC is still the step's. */
	private static final int WANDER_TILES = 15;
	private static final int NO_ANIMATION = -1;
	/** A crossing has started once the player has had a tick to step in. */
	private static final int CROSSING_START_TICKS = 2;
	private static final int CROSSING_TICKS = 10;
	/** A way in can carry the player a while: the sled ride down the Trollweiss slope takes 18 ticks. */
	private static final int ENTERING_TICKS = 30;
	/** {@code Actor.getHealthRatio} while no health bar is shown: not hit lately. */
	private static final int NO_HEALTH_BAR = -1;
	/** An obelisk row's origin is the south-west corner of its 3 by 3 platform, which teleports whole. */
	private static final int TO_PLATFORM_MIDDLE = 1;
	private static final BasicStroke PATH_STROKE = new BasicStroke(2f);
	private static final int START_DOT_PIXELS = 5;
	private static final int COVER_STEP_PIXELS = 3;
	private static final int[] CONVERSATION = {InterfaceID.ChatLeft.UNIVERSE, InterfaceID.ChatRight.UNIVERSE, InterfaceID.Chatmenu.UNIVERSE};
	private static final String NO_STEP = "";
	/** A tile polygon's corners run south-west, south-east, north-east, north-west: each side faces this neighbour. */
	private static final int[] SIDE_DX = {0, 1, 0, -1};
	private static final int[] SIDE_DY = {-1, 0, 1, 0};
	private static final Stroke AREA_EDGE = new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{8, 8}, 0);

	@Inject
	Client client;
	@Inject
	StepGuide guide;
	@Inject
	RoutePlanner routes;
	@Inject
	ClueHelperConfig config;
	@Inject
	Highlighter highlighter;
	@Inject
	ClueLink clueLink;
	@Inject
	PathObjects paths;
	@Inject
	SceneDoors doors;
	@Inject
	WalkLine walkLine;
	@Inject
	LineTargets targets;
	@Inject
	WorldWalk walker;
	@Inject
	LeftClicks leftClicks;
	@Inject
	ModelSilhouette silhouette;
	@Inject
	ProbeTarget probeTarget;
	@Inject
	ItemManager itemManager;

	int crossingFrom = NOT_CROSSING;
	int enteringFrom = NOT_CROSSING;
	/** A path or way-in object clicked from afar: run to first, its crossing starts once the player is beside it. */
	TileObject runTo;
	boolean runToWayIn;
	int runToTick;
	List<WorldPoint> drawnWalk = List.of();
	/** Client thread. The tiles the line was last drawn through. */
	@Getter
	List<WorldPoint> line = List.of();
	/** Client thread, after this overlay's render in the same frame. The tiles the line shows now; empty when none does. */
	@Getter(AccessLevel.PACKAGE)
	List<WorldPoint> shownLine = List.of();
	Map<TileObject, String> pathObjects = Map.of();
	int lineTick = -1;
	/** The step the open conversation began on; null with no conversation open. */
	String conversationStepKey;

	SceneOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/**
	 * Drawn in the order the three were separate overlays, each part behind its own setting. The areas and the outlines
	 * still show while the line is held back: talking to the NPC they mark, crossing an obstacle or entering a way in.
	 */
	@Override
	public Dimension render(Graphics2D graphics)
	{
		WorldView view = client.getTopLevelWorldView();
		drawGuideLine(graphics, view);
		if (view == null)
		{
			return null;
		}
		if (config.pathLine())
		{
			drawAreas(graphics, view);
		}
		if (config.highlightMenus())
		{
			drawTargets(graphics, view);
		}
		return null;
	}

	private void drawGuideLine(Graphics2D graphics, WorldView view)
	{
		shownLine = List.of();
		noteConversationStep();
		if (!config.pathLine() || isTalkingOnThisStep())
		{
			return;
		}
		startCrossingOnArrival(view);
		expireCrossing();
		if (enteringFrom != NOT_CROSSING && isOver(enteringFrom, ENTERING_TICKS))
		{
			enteringFrom = NOT_CROSSING;
		}
		if (view == null || enteringFrom != NOT_CROSSING)
		{
			return;
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
			return;
		}
		guide.getPickUpTiles().stream().filter(tile -> tile.getPlane() == view.getPlane()).forEach(tile -> drawPickUp(graphics, view, tile));
		Goal goal = goal(view, guide.getCurrent());
		if (goal == null)
		{
			return;
		}
		if (goal.getHere() != null)
		{
			drawPath(graphics, view, goal.getHere(), goal.getWalk(), goal.getEnd());
		}
		if (goal.isMarkTile())
		{
			drawTile(graphics, view, goal.getWalk().getSouthWest());
			drawItemUsedOn(graphics, view, goal.getWalk().getSouthWest());
		}
	}

	/**
	 * The edge of a wide wanderer's range on the ground (Wingstone's desert), so the player knows where to look, of where a
	 * way in's items lie (the Mogre Camp's rocks), and of the area a task works anywhere in (the Dark Altar's reanimation
	 * ground) while walking there or doing it.
	 */
	private void drawAreas(Graphics2D graphics, WorldView view)
	{
		graphics.setColor(Highlighter.COLOUR);
		graphics.setStroke(AREA_EDGE);
		Step step = clueLink.getCurrentStep();
		StepIndex.Wander range = step == null ? null : step.getData().getWander();
		if (range != null)
		{
			drawBox(graphics, view, range.getArea());
			drawBox(graphics, view, range.getExclude());
		}
		drawBox(graphics, view, guide.getPickUpArea());
		SubStep current = guide.getCurrent();
		for (StepIndex.Place place : current == null ? List.<StepIndex.Place>of() : current.getSpans())
		{
			drawBox(graphics, view, place.getSpan());
		}
	}

	/** Around the box's tiles, when it is on the plane in view. */
	private void drawBox(Graphics2D graphics, WorldView view, StepIndex.Box box)
	{
		if (box == null || box.getPlane() != view.getPlane())
		{
			return;
		}
		int west = box.getWest();
		int south = box.getSouth();
		int east = box.getEast() + 1;
		int north = box.getNorth() + 1;
		drawEdge(graphics, view, west, south, east, south);
		drawEdge(graphics, view, east, south, east, north);
		drawEdge(graphics, view, east, north, west, north);
		drawEdge(graphics, view, west, north, west, south);
	}

	/** Corner to corner of the box's tiles, a tile at a time so the line follows the ground; off the scene, nothing. */
	private void drawEdge(Graphics2D graphics, WorldView view, int fromX, int fromY, int toX, int toY)
	{
		int steps = Math.max(Math.abs(toX - fromX), Math.abs(toY - fromY));
		Point previous = null;
		for (int i = 0; i <= steps; i++)
		{
			Point at = corner(view, fromX + Integer.signum(toX - fromX) * i, fromY + Integer.signum(toY - fromY) * i);
			previous = drawTo(graphics, previous, at);
		}
	}

	/** The south-west corner of world tile x, y on the canvas. */
	private Point corner(WorldView view, int x, int y)
	{
		int sceneX = x - view.getBaseX();
		int sceneY = y - view.getBaseY();
		if (sceneX < 1 || sceneY < 1 || sceneX >= view.getSizeX() || sceneY >= view.getSizeY())
		{
			return null;
		}
		LocalPoint local = new LocalPoint(sceneX * Perspective.LOCAL_TILE_SIZE, sceneY * Perspective.LOCAL_TILE_SIZE, view);
		return Perspective.localToCanvas(client, local, view.getPlane());
	}

	/** The object and NPC the guide outlines; the objects an item is used on as the ground they cover. */
	private void drawTargets(Graphics2D graphics, WorldView view)
	{
		TileObject object = targets.object(view);
		int usedItem = guide.getItemUsedOnObjects();
		if (object != null && (usedItem >= 0 || guide.isPlantingPatch()))
		{
			BufferedImage pile = usedItem < 0 ? null : itemManager.getImage(usedItem, guide.getItemUsedOnObjectsQuantity(usedItem), false);
			drawItemOn(graphics, guide.getHighlightObjects(), pile);
		}
		else if (object != null)
		{
			highlighter.object(graphics, object, guide.getFillObjects().contains(object));
		}
		NPC npc = targets.npc();
		if (npc != null && !clueLink.coreMarks(npc))
		{
			highlighter.npc(graphics, npc, guide.getFillNpcs().contains(npc));
		}
	}

	/**
	 * Objects an item is used on are marked as a hot/cold spot is: one outline around the ground all their one-tile pieces
	 * cover (the crevice is nine, a patch one per tile), the item, if any, shown on each of them (a place's patches). Their
	 * models would trace every plant on a patch.
	 */
	private void drawItemOn(Graphics2D graphics, List<TileObject> objects, BufferedImage icon)
	{
		Set<WorldPoint> tiles = objects.stream().map(TileObject::getWorldLocation).collect(Collectors.toSet());
		Path2D outline = new Path2D.Float();
		objects.forEach(piece -> addOuterSides(outline, piece, tiles));
		if (outline.getCurrentPoint() == null)
		{
			return;
		}
		highlighter.ground(graphics, outline);
		if (icon != null)
		{
			objects.stream().collect(Collectors.groupingBy(TileObject::getId)).values()
				.forEach(pieces -> drawOnTile(graphics, middlePiece(pieces), icon));
		}
	}

	/**
	 * The piece nearest the object's middle on the ground, where the item is shown: an L-shaped patch's middle lies outside
	 * it. Picked on the ground, not on screen, so it stays put as the camera turns.
	 */
	private static TileObject middlePiece(List<TileObject> pieces)
	{
		double x = pieces.stream().mapToInt(piece -> piece.getWorldLocation().getX()).average().orElseThrow();
		double y = pieces.stream().mapToInt(piece -> piece.getWorldLocation().getY()).average().orElseThrow();
		return pieces.stream().min(Comparator.comparingDouble(piece -> Point2D.distanceSq(piece.getWorldLocation().getX(),
			piece.getWorldLocation().getY(), x, y))).orElseThrow();
	}

	private static void drawOnTile(Graphics2D graphics, TileObject piece, BufferedImage icon)
	{
		Polygon tile = piece.getCanvasTilePoly();
		if (tile != null)
		{
			Rectangle middle = tile.getBounds();
			graphics.drawImage(icon, (int) middle.getCenterX() - icon.getWidth() / 2, (int) middle.getCenterY() - icon.getHeight() / 2, null);
		}
	}

	/** The sides of the piece's tile that no other piece's tile shares; drawn side by side, rather than joined each frame. */
	private static void addOuterSides(Path2D outline, TileObject piece, Set<WorldPoint> tiles)
	{
		Polygon tile = piece.getCanvasTilePoly();
		if (tile == null || tile.npoints != SIDE_DX.length)
		{
			return;
		}
		WorldPoint at = piece.getWorldLocation();
		for (int side = 0; side < SIDE_DX.length; side++)
		{
			if (!tiles.contains(at.dx(SIDE_DX[side]).dy(SIDE_DY[side])))
			{
				int end = (side + 1) % SIDE_DX.length;
				outline.moveTo(tile.xpoints[side], tile.ypoints[side]);
				outline.lineTo(tile.xpoints[end], tile.ypoints[end]);
			}
		}
	}

	/** Where the line leads for the current sub-step; null when nothing is drawn. */
	private Goal goal(WorldView view, SubStep current)
	{
		if (current != null && current.getKind() == SubStep.Kind.ACT && isFighting(actNpc(current)))
		{
			return null;
		}
		WorldPoint waypoint = current == null ? null : current.getWaypoint();
		if (waypoint == null && current != null)
		{
			if (current.getKind() == SubStep.Kind.WALK_TO_CLUE && current.getGoal() != null)
			{
				return pathToClue(view, current);
			}
			if (current.isPreparation() && current.getNpcIds().isEmpty())
			{
				return null;
			}
			if (isActWithoutPlace(current))
			{
				WorldPoint act = actTarget(view, current, current.getTab() == StepGuide.Tab.EMOTES ? SubSteps.ARRIVE_TILES : 1);
				return act == null ? null : path(view, WalkGoal.tile(act), false);
			}
		}
		if (!walkableWaypoint(waypoint, view) && !guide.getHighlightObjects().isEmpty())
		{
			return toObject(view);
		}
		NPC target = waypoint == null && current != null && current.getKind() == SubStep.Kind.ACT ? targets.npc() : null;
		if (target != null && walkableWaypoint(target.getWorldLocation(), view))
		{
			return approach(target);
		}
		if (waypoint == null)
		{
			return null;
		}
		if (guide.isObeliskActivated())
		{
			WorldPoint middle = waypoint.dx(TO_PLATFORM_MIDDLE).dy(TO_PLATFORM_MIDDLE);
			return path(view, WalkGoal.tile(middle), middle.getPlane() == view.getPlane());
		}
		TileObject object = outlined() ? targets.object(view) : null;
		NPC approached = object == null && approachOutlined() ? targets.npc() : null;
		if (approached != null)
		{
			return approach(approached);
		}
		if (object != null && besidePlayer(view, object))
		{
			return null;
		}
		boolean markTile = !outlined() && !awaitsNpc() && waypoint.getPlane() == view.getPlane() && !guide.getPickUpTiles().contains(waypoint)
			&& !coreMarksDigSpot(waypoint);
		return object == null ? path(view, WalkGoal.tile(waypoint), markTile) : pathTo(view, targets.planWalk(), object, markTile);
	}

	/** Core marks a hot/cold clue's dig spots itself; a feel's tile is ours to mark. */
	private boolean coreMarksDigSpot(WorldPoint tile)
	{
		Step travel = routes.travelStep();
		return travel != null && ClueTypes.HOT_COLD.equals(travel.getType()) && travel.getWaypointAct() != null
			&& travel.getActItems().isEmpty() && travel.getTargets().contains(tile);
	}

	private Goal path(WorldView view, WalkGoal walk, boolean markTile)
	{
		return new Goal(targets.planWalk(), walk, endOn(view, walk), markTile);
	}

	private Goal pathTo(WorldView view, WalkCosts here, TileObject object, boolean markTile)
	{
		return new Goal(here, LineTargets.objectGoal(view, object), endIn(view, object), markTile);
	}

	/** Core marks the clue itself; only the way there is ours, and the tile of an act core doesn't mark. */
	private Goal pathToClue(WorldView view, SubStep current)
	{
		WorldPoint actTile = unmarkedActTileAhead();
		if (actTile != null)
		{
			return path(view, WalkGoal.tile(actTile), true);
		}
		WorldPoint goal = wandererOr(view, current.getGoal());
		return goal == null ? null : path(view, WalkGoal.tile(goal), false);
	}

	/**
	 * A three-step's drop or a hot/cold feel: core marks neither, so the line leads to the act's tile and marks it from the
	 * walk on, past the walk node the route ends at.
	 */
	private WorldPoint unmarkedActTileAhead()
	{
		Step travel = routes.travelStep();
		SubStep next = guide.getNext();
		boolean coreMarksNoTile = travel != null && (ClueTypes.THREE_STEP.equals(travel.getType()) || ClueTypes.HOT_COLD.equals(travel.getType()));
		boolean unmarked = coreMarksNoTile && next != null && next.getKind() == SubStep.Kind.ACT && next.getWaypoint() != null
			&& next.getGroundItem() < 0 && !coreMarksDigSpot(next.getWaypoint());
		return unmarked ? next.getWaypoint() : null;
	}

	/**
	 * The step's own act, one part of a three-step or the kill for a key, with nothing outlined to walk to and no item to
	 * click. An outlined NPC (a hellhound to kill) is walked to instead, the nearest one.
	 */
	private boolean isActWithoutPlace(SubStep current)
	{
		boolean stepOrPartAct = current.getStage() == null || current.getStage().getKind() == Stage.Kind.PART || isKillForKey(current);
		return current.getKind() == SubStep.Kind.ACT && stepOrPartAct && current.getActItemNamed() == null && !outlined();
	}

	/**
	 * The items to pick up, outlined and filled where they lie (Take is their left click). Nothing once they are gone,
	 * though the tile list is only searched again next tick.
	 */
	private void drawPickUp(Graphics2D graphics, WorldView view, WorldPoint at)
	{
		Tile tile = SceneObjects.tile(view, at);
		ItemLayer items = tile == null ? null : tile.getItemLayer();
		if (items != null)
		{
			highlighter.object(graphics, items, true);
		}
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

	private void drawItemUsedOn(Graphics2D graphics, WorldView view, WorldPoint tile)
	{
		int itemId = itemUsedOn(tile);
		LocalPoint local = itemId < 0 ? null : LocalPoint.fromWorld(view, tile);
		if (local != null)
		{
			OverlayUtil.renderImageLocation(client, graphics, local, itemManager.getImage(itemId), 0);
		}
	}

	/** The device to feel with on the tile, or the item its act uses there by an option (the master clue to drop); else -1. */
	private int itemUsedOn(WorldPoint tile)
	{
		HotColdTier tier = probeTarget.feltOn(tile);
		if (tier != null)
		{
			return tier.getDeviceItemId();
		}
		SubStep act = Stream.of(guide.getCurrent(), guide.getNext())
			.filter(sub -> sub != null && (sub.getKind() == SubStep.Kind.ACT || sub.getKind() == SubStep.Kind.ENTER) && sub.getActOption() != null
				&& tile.equals(sub.getWaypoint()))
			.findFirst().orElse(null);
		return act == null || act.getActItems().size() != 1 ? -1 : act.getActItems().iterator().next();
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
		WorldPoint at = playerTile();
		if (step == null || at == null || ClueTypes.HOT_COLD.equals(step.getType()))
		{
			return null;
		}
		boolean killForKey = isKillForKey(current);
		WorldPoint target = killForKey ? nearestNpc(view, keyHolder(step, current.getStage()), at) : nearestNamedNpc(view, step.getNpcsAtStep(), at);
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
		WorldPoint at = playerTile();
		boolean looking = range != null && at != null && range.contains(at);
		return looking ? null : goal;
	}

	/** An NPC the act is done with: its own, the step's at the clue, or the one dropping the key. */
	private Predicate<NPC> actNpc(SubStep act)
	{
		Step step = clueLink.getCurrentStep();
		Predicate<NPC> own = npc -> act.getNpcIds().contains(npc.getId());
		if (step == null)
		{
			return own;
		}
		Predicate<NPC> atStep = own.or(npc -> isNamed(npc, step.getNpcsAtStep()));
		return isKillForKey(act) ? atStep.or(keyHolder(step, act.getStage())) : atStep;
	}

	/** The NPC dropping the key: the stage's own (the Mercenary Captain), else core's for the clue. */
	private static Predicate<NPC> keyHolder(Step step, Stage kill)
	{
		return kill.getNpcIds().isEmpty() ? npc -> isNamed(npc, step.getNpcs()) : npc -> kill.getNpcIds().contains(npc.getId());
	}

	private static WorldPoint nearestNamedNpc(WorldView view, List<String> names, WorldPoint at)
	{
		if (names == null || names.isEmpty())
		{
			return null;
		}
		return nearestNpc(view, npc -> isNamed(npc, names), at);
	}

	static boolean isNamed(NPC npc, List<String> names)
	{
		return npc.getName() != null && names.stream().anyMatch(name -> name.equalsIgnoreCase(npc.getName()));
	}

	private static WorldPoint nearestNpc(WorldView view, Predicate<NPC> wanted, WorldPoint at)
	{
		return view.npcs().stream().filter(wanted).map(npc -> shownTile(npc, 1)).min(Comparator.comparingInt(tile -> tile.distanceTo2D(at)))
			.orElse(null);
	}

	private void noteConversationStep()
	{
		if (!inConversation())
		{
			conversationStepKey = null;
		}
		else if (conversationStepKey == null && isTalkStep())
		{
			conversationStepKey = currentStepKey();
		}
	}

	/** A dig or a search has no one to talk to: a chat on the way (a cave's exit) doesn't end the walk. */
	private boolean isTalkStep()
	{
		Step step = clueLink.getCurrentStep();
		return step != null && !step.getNpcsAtStep().isEmpty();
	}

	/**
	 * Once the player is talking on a talk step, the way there is done and the chat options carry the step. A step the
	 * talk moved on to (the next clue handed over) gets its line while the chat is still open.
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
		return Arrays.stream(CONVERSATION).anyMatch(dialogue -> Widgets.shown(client, dialogue));
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
		return current != null && !current.getNpcIds().isEmpty() || !guide.getHighlights().getMenuTarget().getNpcIds().isEmpty();
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
	private Goal approach(NPC npc)
	{
		WorldPoint from = playerTile();
		WorldPoint at = shownTile(npc, 1);
		return from == null || from.distanceTo2D(at) <= 1 ? null : new Goal(targets.planWalk(), WalkGoal.tile(at), endIn(npc), false);
	}

	/** The walk table's tiles for a house are not the instance's own, so in a house the object leads the way. */
	private static boolean walkableWaypoint(WorldPoint waypoint, WorldView view)
	{
		return waypoint != null && view != null && !view.isInstance();
	}

	/** A nexus, altar or other object the step uses, where the step has no tile in the loaded scene to walk to. */
	private Goal toObject(WorldView view)
	{
		WalkCosts here = targets.walk(view);
		TileObject object = here == null ? null : targets.object(view);
		return object == null || besidePlayer(view, object) ? null : pathTo(view, here, object, false);
	}

	private boolean besidePlayer(WorldView view, TileObject object)
	{
		WorldPoint at = playerTile();
		return at != null && isBeside(at, LineTargets.objectGoal(view, object));
	}

	/** On the area or one tile round it, diagonals included. */
	private static boolean isBeside(WorldPoint at, WalkGoal area)
	{
		return new WorldArea(area.getSouthWest(), area.getSizeX(), area.getSizeY()).distanceTo(at) <= 1;
	}

	/**
	 * Null when the object's model can't be read this frame (see Highlighter#object): the line then stops beside it, as
	 * it would be drawn over the model otherwise.
	 */
	private LineEnd endIn(WorldView view, TileObject object)
	{
		try
		{
			return new LineEnd(object.getLocalLocation(), silhouette.of(object), LineTargets.objectGoal(view, object));
		}
		catch (NullPointerException e)
		{
			return null;
		}
	}

	private LineEnd endIn(NPC npc)
	{
		NPCComposition composition = npc.getTransformedComposition();
		int size = composition == null ? 1 : composition.getSize();
		return npc.getModel() == null ? null
			: new LineEnd(npc.getLocalLocation(), silhouette.of(npc), WalkGoal.area(shownTile(npc, size), size, size));
	}

	/**
	 * The south-west tile the NPC is drawn on. Its world location is the tile it walks to, a step ahead of its model: a
	 * line led there would pass the NPC and turn back into it.
	 */
	private static WorldPoint shownTile(NPC npc, int size)
	{
		LocalPoint middle = npc.getLocalLocation();
		int toCorner = (size - 1) * Perspective.LOCAL_HALF_TILE_SIZE;
		return WorldPoint.fromLocal(npc.getWorldView(), middle.getX() - toCorner, middle.getY() - toCorner, npc.getWorldLocation().getPlane());
	}

	/** @param end what the line ends in; null when it ends on a plain tile */
	private void drawPath(Graphics2D graphics, WorldView view, WalkCosts here, WalkGoal goal, LineEnd end)
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
		if (end != null && !line.isEmpty())
		{
			drawInto(graphics, view, line.get(line.size() - 1), end);
		}
	}

	/** From the line's last tile on to the middle of what it ends in, where that tile is beside it. */
	private void drawInto(Graphics2D graphics, WorldView view, WorldPoint last, LineEnd end)
	{
		LocalPoint lastLocal = onFloor(view, last);
		if (lastLocal == null || end.getMiddle() == null || !isBeside(last, end.getFootprint()))
		{
			return;
		}
		Point from = Perspective.localToCanvas(client, lastLocal, view.getPlane());
		Point to = Perspective.localToCanvas(client, end.getMiddle(), view.getPlane());
		if (from != null && to != null)
		{
			drawTo(graphics, from, end.getModel() == null ? to : untilCovered(from, to, end.getModel()));
		}
	}

	/**
	 * A single tile the walk can't stand on, such as core's clue tile under a crate: the line runs on to its middle,
	 * hidden under what stands there. Null for an area, which the walk ends inside.
	 */
	private LineEnd endOn(WorldView view, WalkGoal goal)
	{
		WorldPoint tile = goal.getSouthWest();
		LocalPoint middle = goal.getSizeX() == 1 && goal.getSizeY() == 1 ? onFloor(view, tile) : null;
		if (middle == null)
		{
			return null;
		}
		TileObject standing = objectStandingOn(view, middle);
		try
		{
			return new LineEnd(middle, standing == null ? null : silhouette.of(standing), goal);
		}
		catch (NullPointerException e)
		{
			return null;
		}
	}

	private static TileObject objectStandingOn(WorldView view, LocalPoint middle)
	{
		Tile tile = view.getScene().getTiles()[view.getPlane()][middle.getSceneX()][middle.getSceneY()];
		if (tile == null)
		{
			return null;
		}
		for (GameObject object : tile.getGameObjects())
		{
			if (object != null && object.getRenderable() != null && !(object.getRenderable() instanceof Actor))
			{
				return object;
			}
		}
		return tile.getWallObject() != null ? tile.getWallObject() : tile.getDecorativeObject();
	}

	/**
	 * Where the segment first meets the model, so the line seems to pass under it. Stepped along a few pixels at a time:
	 * a model's shape holds thousands of faces, too many to cut out of the clip each frame.
	 */
	private static Point untilCovered(Point from, Point to, Shape model)
	{
		double length = Math.hypot(to.getX() - from.getX(), to.getY() - from.getY());
		int steps = Math.max(1, (int) Math.ceil(length / COVER_STEP_PIXELS));
		Point reached = from;
		for (int i = 1; i <= steps; i++)
		{
			Point at = new Point(from.getX() + (to.getX() - from.getX()) * i / steps, from.getY() + (to.getY() - from.getY()) * i / steps);
			if (model.contains(at.getX(), at.getY()))
			{
				return reached;
			}
			reached = at;
		}
		return to;
	}

	/** The line moves on with the player, and the objects it still leads through are found again. */
	private void followAhead(WorldView view)
	{
		walkLine.advance();
		pathObjects = paths.along(view, walkTable(view), walkLine.getAhead());
	}

	/**
	 * A row object on the way that opens only once another is used, or the Abyss obstacle picked
	 * in place of the spot the walk data names, as the guide outlines it: the line leads there first. Beside the Abyss
	 * obstacle there is no line until the player is through: the walk data's way on starts from its own spot, across
	 * the ring wall.
	 */
	private List<WorldPoint> toUseFirst(WorldView view, WalkCosts here, List<WorldPoint> ahead)
	{
		TileObject first = Stream.concat(guide.getHighlightObjects().stream(), pathObjects.keySet().stream())
			.filter(object -> PathObjects.isUsedFirst(object.getId()) || AbyssObstacles.isObstacle(object.getId()))
			.findFirst().orElse(null);
		WorldPoint at = playerTile();
		if (first == null || at == null)
		{
			return ahead;
		}
		if (besidePlayer(view, first))
		{
			return AbyssObstacles.isObstacle(first.getId()) ? List.of() : ahead;
		}
		List<WorldPoint> there = here.getScene() == null ? List.of()
			: walker.path(Ground.sceneOnly(here.getScene()), at, LineTargets.objectGoal(view, first));
		if (!there.isEmpty())
		{
			return there;
		}
		// The walk data's own spot may be across the ring from the obstacle picked: the line still points at the outlined one
		return AbyssObstacles.isObstacle(first.getId()) ? List.of(at, first.getWorldLocation()) : ahead;
	}

	/**
	 * Attacking one of the {@code wanted} NPCs, its health bar up: the fight is where the player is, so no line. Being
	 * attacked by one keeps it (aggressive naguas hit on arrival). Running to an NPC clicked from afar targets it too,
	 * before any hit, and keeps the line.
	 */
	private boolean isFighting(Predicate<NPC> wanted)
	{
		Player local = client.getLocalPlayer();
		Actor opponent = local == null ? null : local.getInteracting();
		return opponent instanceof NPC && wanted.test((NPC) opponent) && opponent.getHealthRatio() != NO_HEALTH_BAR;
	}

	private WorldPoint playerTile()
	{
		Player local = client.getLocalPlayer();
		return local == null ? null : local.getWorldLocation();
	}

	/**
	 * Client thread. Taking a gate, cart or shortcut the line leads through: the player stands inside it or is carried
	 * across, where a walk starts from a tile it can't leave, so the line moves on with them but isn't searched again
	 * until they are through. Through it, they may have gone back the way they came: the line picks its walk again.
	 */
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		runTo = null;
		WorldView view = client.getTopLevelWorldView();
		boolean wayIn = isWayInClicked(event);
		TileObject clicked = Stream.concat(pathObjects.keySet().stream(), wayIn ? guide.getHighlightObjects().stream() : Stream.empty())
			.filter(object -> object.getId() == event.getId()).findFirst().orElse(null);
		if (clicked == null || view == null || !wayIn && opensInPlace(view, clicked))
		{
			return;
		}
		if (besidePlayer(view, clicked))
		{
			startCrossing(wayIn);
			return;
		}
		runTo = clicked;
		runToWayIn = wayIn;
		runToTick = client.getTickCount();
	}

	/**
	 * A door opened leaves the player where they stand, so it is no crossing: the line keeps following its goal (an NPC
	 * walking about behind the door).
	 */
	private boolean opensInPlace(WorldView view, TileObject clicked)
	{
		return doors.opensInPlace(walkTable(view), clicked);
	}

	/**
	 * Clicked from afar, the object is run to first, the line still leading there (an Abyss obstacle's own spot, a
	 * shortcut across the room); the crossing starts on arriving beside it. Given up after a while.
	 */
	private void startCrossingOnArrival(WorldView view)
	{
		if (runTo == null || view == null)
		{
			return;
		}
		if (besidePlayer(view, runTo))
		{
			startCrossing(runToWayIn);
			runTo = null;
		}
		else if (client.getTickCount() - runToTick > ENTERING_TICKS)
		{
			runTo = null;
		}
	}

	private void startCrossing(boolean wayIn)
	{
		if (wayIn)
		{
			enteringFrom = client.getTickCount();
		}
		else
		{
			crossingFrom = client.getTickCount();
		}
	}

	/** The step's way in taken (a slope slid down): no line is drawn until the player is through and stands idle. */
	private boolean isWayInClicked(MenuOptionClicked event)
	{
		SubStep current = guide.getCurrent();
		return current != null && current.getKind() == SubStep.Kind.ENTER
			&& guide.getHighlights().getMenuTarget().getObjectIds().contains(event.getId());
	}

	/** Client thread. The items used on objects the line leads through: a strange old lockpick on a Barrows door. */
	public Set<Integer> pathItems()
	{
		return pathObjects.keySet().stream().map(object -> doors.lockpickFor(object.getId())).filter(itemId -> itemId != -1)
			.collect(Collectors.toUnmodifiableSet());
	}

	/** The item used on a path object, drawn in the middle of it. */
	private void drawItemUsedOn(Graphics2D graphics, TileObject object)
	{
		int itemId = doors.lockpickFor(object.getId());
		Shape box = itemId == -1 ? null : object.getClickbox();
		if (box != null)
		{
			BufferedImage image = itemManager.getImage(itemId);
			Rectangle bounds = box.getBounds();
			graphics.drawImage(image, (int) bounds.getCenterX() - image.getWidth() / 2, (int) bounds.getCenterY() - image.getHeight() / 2, null);
		}
	}

	/** Client thread. Each object the line leads through, with its option, for the right-click menu to colour. */
	public List<MenuTarget> pathObjectTargets()
	{
		List<MenuTarget> targets = new ArrayList<>();
		pathObjects.forEach((object, option) -> targets.add(MenuTarget.builder().objectIds(Set.of(object.getId()))
			.path(List.of(option)).build()));
		return targets;
	}

	/** A crossing ends once its time is up, or once the player has had a tick to step in and stands idle: the line then picks its walk again. */
	private void expireCrossing()
	{
		if (crossingFrom != NOT_CROSSING && isOver(crossingFrom, CROSSING_TICKS))
		{
			crossingFrom = NOT_CROSSING;
			walkLine.forget();
		}
	}

	/** Its time is up, or the player has had a tick to step in and stands idle. */
	private boolean isOver(int fromTick, int maxTicks)
	{
		Player local = client.getLocalPlayer();
		int since = client.getTickCount() - fromTick;
		boolean idle = local != null && local.getAnimation() == NO_ANIMATION && local.getPoseAnimation() == local.getIdlePoseAnimation();
		return since > maxTicks || since >= CROSSING_START_TICKS && idle;
	}

	private boolean isCrossing()
	{
		return crossingFrom != NOT_CROSSING;
	}

	/**
	 * Over a crossing (a wall searched, stepping stones, a ladder) the line breaks: it runs into the outlined object and
	 * goes on from a dot where the crossing lands, so it never reads as a walk through the wall or over the water.
	 */
	private void drawLine(Graphics2D graphics, WorldView view, List<WorldPoint> ahead)
	{
		pathObjects.forEach((object, option) -> highlighter.object(graphics, object, leftClicks.isLeftClick(object, option)));
		pathObjects.keySet().forEach(object -> drawItemUsedOn(graphics, object));
		shownLine = ahead;
		graphics.setColor(Highlighter.COLOUR);
		graphics.setStroke(PATH_STROKE);
		WalkTable table = walkTable(view);
		Point previous = null;
		WorldPoint previousTile = null;
		boolean crossed = false;
		for (WorldPoint tile : ahead)
		{
			if (SKIPPED_TILES.contains(tile))
			{
				continue;
			}
			if (previousTile != null && PathObjects.isCrossing(table, previousTile, tile))
			{
				drawIntoCrossed(graphics, view, previous, previousTile);
				previous = null;
				crossed = true;
			}
			LocalPoint local = onFloor(view, tile);
			Point at = local == null ? null : Perspective.localToCanvas(client, local, view.getPlane());
			if (crossed && at != null)
			{
				double radius = START_DOT_PIXELS / 2.0;
				graphics.fill(new Ellipse2D.Double(at.getX() - radius, at.getY() - radius, START_DOT_PIXELS, START_DOT_PIXELS));
				crossed = false;
			}
			previous = drawTo(graphics, previous, at);
			previousTile = tile;
		}
	}

	/** On into the outlined object nearest where the crossing starts, until its model covers the line. */
	private void drawIntoCrossed(Graphics2D graphics, WorldView view, Point from, WorldPoint start)
	{
		TileObject crossed = pathObjects.keySet().stream().filter(object -> object.getPlane() == start.getPlane()
				&& object.getWorldLocation().distanceTo2D(start) <= PathObjects.ROW_OBJECT_TILES)
			.min(Comparator.comparingInt(object -> object.getWorldLocation().distanceTo2D(start))).orElse(null);
		Point to = crossed == null ? null : Perspective.localToCanvas(client, crossed.getLocalLocation(), view.getPlane());
		if (from == null || to == null)
		{
			return;
		}
		try
		{
			drawTo(graphics, from, untilCovered(from, to, silhouette.of(crossed)));
		}
		catch (NullPointerException e)
		{
			drawTo(graphics, from, to);
		}
	}

	/** The walk data's table; null before it is loaded, and in a house, whose tiles are not the table's. */
	private WalkTable walkTable(WorldView view)
	{
		WalkData data = routes.getWalk();
		return data == null || view.isInstance() ? null : data.getTable();
	}

	/** Null off the view's floor or outside its scene. */
	static LocalPoint onFloor(WorldView view, WorldPoint tile)
	{
		return tile.getPlane() == view.getPlane() ? LocalPoint.fromWorld(view, tile) : null;
	}

	/** The segment from one canvas point to the next, where both are on screen; the point drawn to, for the next segment. */
	static Point drawTo(Graphics2D graphics, Point from, Point to)
	{
		if (from != null && to != null)
		{
			graphics.drawLine(from.getX(), from.getY(), to.getX(), to.getY());
		}
		return to;
	}

	/** What the line leads to: a tile, an NPC to approach, or the outlined objects' nearest side. */
	@Value
	private static class Goal
	{
		/** The walk the line is laid over; null draws no line. */
		WalkCosts here;
		WalkGoal walk;
		/** What the line ends in; null when it ends on a plain tile. */
		LineEnd end;
		/** The goal's tile is marked, as nothing on it is outlined. */
		boolean markTile;
	}

	/** The object or NPC the line ends in: it runs on from the tile beside it to its middle, hidden under its model. */
	@Value
	private static class LineEnd
	{
		LocalPoint middle;
		Shape model;
		WalkGoal footprint;
	}
}
