package com.cluehelper.ui;

import java.awt.Shape;
import java.awt.geom.Path2D;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Renderable;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;

/**
 * The object's visible faces as one shape. Its clickbox won't do: a model that uses its bounding box as the clickbox
 * (Xeric's talisman, fairy rings, spirit trees) would fill as a box. Positions follow core's ModelOutlineRenderer, so
 * the fill sits inside the outline.
 */
final class ModelSilhouette
{
	private static final int OFF_SCREEN = Integer.MIN_VALUE;
	private static final int HIDDEN_FACE = -2;
	private static final int SEE_THROUGH = 254;
	private static final int NO_ROTATION = 0;

	private ModelSilhouette()
	{
	}

	static Shape of(Client client, TileObject object)
	{
		Path2D.Float shape = new Path2D.Float(Path2D.WIND_NON_ZERO);
		if (object instanceof GameObject)
		{
			GameObject game = (GameObject) object;
			addFaces(shape, client, object, game.getRenderable(), 0, 0, game.getModelOrientation());
		}
		else if (object instanceof DecorativeObject)
		{
			DecorativeObject decoration = (DecorativeObject) object;
			addFaces(shape, client, object, decoration.getRenderable(), decoration.getXOffset(), decoration.getYOffset(), NO_ROTATION);
			addFaces(shape, client, object, decoration.getRenderable2(), decoration.getXOffset2(), decoration.getYOffset2(), NO_ROTATION);
		}
		else if (object instanceof WallObject)
		{
			WallObject wall = (WallObject) object;
			addFaces(shape, client, object, wall.getRenderable1(), 0, 0, NO_ROTATION);
			addFaces(shape, client, object, wall.getRenderable2(), 0, 0, NO_ROTATION);
		}
		else if (object instanceof GroundObject)
		{
			addFaces(shape, client, object, ((GroundObject) object).getRenderable(), 0, 0, NO_ROTATION);
		}
		return shape;
	}

	/** Back faces are left out, so every face added winds the same way and overlaps fill once. */
	private static void addFaces(Path2D shape, Client client, TileObject object, Renderable renderable, int xOffset, int yOffset,
		int orientation)
	{
		Model model = renderable instanceof Model ? (Model) renderable : renderable == null ? null : renderable.getModel();
		if (model == null)
		{
			return;
		}
		int count = model.getVerticesCount();
		int[] x = new int[count];
		int[] y = new int[count];
		Perspective.modelToCanvas(client, object.getWorldView(), count, object.getX() + xOffset, object.getY() + yOffset,
			object.getZ() - renderable.getAnimationHeightOffset(), orientation, model.getVerticesX(), model.getVerticesZ(),
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
			if (onScreen && isDrawn(model, face) && facesCamera(x[a], y[a], x[b], y[b], x[c], y[c]))
			{
				shape.moveTo(x[a], y[a]);
				shape.lineTo(x[b], y[b]);
				shape.lineTo(x[c], y[c]);
				shape.closePath();
			}
		}
	}

	private static boolean isDrawn(Model model, int face)
	{
		byte[] transparencies = model.getFaceTransparencies();
		boolean seeThrough = transparencies != null && (transparencies[face] & 0xFF) >= SEE_THROUGH;
		return !seeThrough && model.getFaceColors3()[face] != HIDDEN_FACE;
	}

	private static boolean facesCamera(int x1, int y1, int x2, int y2, int x3, int y3)
	{
		return (y2 - y1) * (x3 - x2) - (x2 - x1) * (y3 - y2) > 0;
	}
}
