package com.cluehelper.ui;

import java.awt.Shape;
import java.awt.geom.Path2D;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;

/**
 * The object's or NPC's visible faces as one shape. Its clickbox won't do: a model that uses its bounding box as the clickbox
 * (Xeric's talisman, fairy rings, spirit trees) would fill as a box. Positions follow core's ModelOutlineRenderer, so
 * the fill sits inside the outline.
 */
@Singleton
final class ModelSilhouette
{
	private static final int OFF_SCREEN = Integer.MIN_VALUE;
	/** A face the client never draws. */
	private static final int HIDDEN_FACE = -2;
	/** RuneLite's outline renderer skips faces this transparent. */
	private static final int SEE_THROUGH = 254;
	private static final int NO_ROTATION = 0;

	@Inject
	private Client client;

	Shape of(TileObject object)
	{
		Path2D.Float shape = new Path2D.Float(Path2D.WIND_NON_ZERO);
		if (object instanceof GameObject)
		{
			GameObject game = (GameObject) object;
			addFaces(shape, object, game.getRenderable(), 0, 0, game.getModelOrientation());
		}
		else if (object instanceof DecorativeObject)
		{
			DecorativeObject decoration = (DecorativeObject) object;
			addFaces(shape, object, decoration.getRenderable(), decoration.getXOffset(), decoration.getYOffset(), NO_ROTATION);
			addFaces(shape, object, decoration.getRenderable2(), decoration.getXOffset2(), decoration.getYOffset2(), NO_ROTATION);
		}
		else if (object instanceof WallObject)
		{
			WallObject wall = (WallObject) object;
			addFaces(shape, object, wall.getRenderable1(), 0, 0, NO_ROTATION);
			addFaces(shape, object, wall.getRenderable2(), 0, 0, NO_ROTATION);
		}
		else if (object instanceof GroundObject)
		{
			addFaces(shape, object, ((GroundObject) object).getRenderable(), 0, 0, NO_ROTATION);
		}
		return shape;
	}

	Shape of(NPC npc)
	{
		Path2D.Float shape = new Path2D.Float(Path2D.WIND_NON_ZERO);
		LocalPoint at = npc.getLocalLocation();
		WorldView view = npc.getWorldView();
		if (at != null && view != null)
		{
			int z = Perspective.getTileHeight(client, at, view.getPlane()) - npc.getAnimationHeightOffset();
			addFaces(shape, view, npc, at.getX(), at.getY(), z, npc.getCurrentOrientation());
		}
		return shape;
	}

	private void addFaces(Path2D shape, TileObject object, Renderable renderable, int xOffset, int yOffset,
		int orientation)
	{
		if (renderable != null)
		{
			addFaces(shape, object.getWorldView(), renderable, object.getX() + xOffset, object.getY() + yOffset,
				object.getZ() - renderable.getAnimationHeightOffset(), orientation);
		}
	}

	/** Every face is added wound the same way, so overlaps fill once and the shape is the whole model's. */
	private void addFaces(Path2D shape, WorldView view, Renderable renderable, int localX, int localY, int z,
		int orientation)
	{
		Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
		if (model == null)
		{
			return;
		}
		int count = model.getVerticesCount();
		int[] x = new int[count];
		int[] y = new int[count];
		Perspective.modelToCanvas(client, view, count, localX, localY, z, orientation, model.getVerticesX(), model.getVerticesZ(),
			model.getVerticesY(), x, y);
		int[] first = model.getFaceIndices1();
		int[] second = model.getFaceIndices2();
		int[] third = model.getFaceIndices3();
		for (int face = 0; face < model.getFaceCount(); face++)
		{
			int a = first[face];
			int b = second[face];
			int c = third[face];
			boolean onScreen = y[a] != OFF_SCREEN && y[b] != OFF_SCREEN && y[c] != OFF_SCREEN;
			if (onScreen && isDrawn(model, face))
			{
				// Turned to wind as the faces facing the camera do: a flat model wound the other way (Doris' trapdoor) still fills
				boolean facing = facesCamera(x[a], y[a], x[b], y[b], x[c], y[c]);
				int next = facing ? b : c;
				int last = facing ? c : b;
				shape.moveTo(x[a], y[a]);
				shape.lineTo(x[next], y[next]);
				shape.lineTo(x[last], y[last]);
				shape.closePath();
			}
		}
	}

	/** Neither skipped by the outline renderer as see-through nor never drawn by the client. */
	static boolean isDrawn(Model model, int face)
	{
		byte[] transparencies = model.getFaceTransparencies();
		int[] colours = model.getFaceColors3();
		boolean seeThrough = transparencies != null && (transparencies[face] & 0xFF) >= SEE_THROUGH;
		return !seeThrough && (colours == null || colours[face] != HIDDEN_FACE);
	}

	private static boolean facesCamera(int x1, int y1, int x2, int y2, int x3, int y3)
	{
		return (y2 - y1) * (x3 - x2) - (x2 - x1) * (y3 - y2) > 0;
	}
}
