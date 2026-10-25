package com.cluehelper.guide;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/** Questions about a widget and the widgets under it. */
public final class Widgets
{
	private Widgets()
	{
	}

	/** The widget and everything under it, depth first. */
	public static List<Widget> descendants(Widget widget)
	{
		List<Widget> out = new ArrayList<>();
		if (widget == null)
		{
			return out;
		}
		out.add(widget);
		for (Widget[] children : new Widget[][]{widget.getStaticChildren(), widget.getDynamicChildren(), widget.getNestedChildren()})
		{
			for (int i = 0; children != null && i < children.length; i++)
			{
				out.addAll(descendants(children[i]));
			}
		}
		return out;
	}

	/** Not hidden, and with a width on screen. */
	static boolean visible(Widget widget)
	{
		return widget != null && !widget.isHidden() && widget.getBounds() != null && widget.getBounds().width > 0;
	}

	static boolean hasText(Widget widget)
	{
		return widget.getText() != null && !Text.removeTags(widget.getText()).isBlank();
	}

	/** A nexus row is a container whose text sits in its children: the text is recoloured, so no box is drawn. */
	static boolean hasTextInside(Widget widget)
	{
		return descendants(widget).stream().anyMatch(Widgets::hasText);
	}

	static boolean hasAction(Widget widget, String action)
	{
		for (String a : widget.getActions())
		{
			if (action.equalsIgnoreCase(a))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Rows scrolled out of view keep {@code hidden = false} and their bounds, and the list's layer is as tall as all its
	 * rows, so only clipping to every ancestor leaves the visible part.
	 */
	static Rectangle clipped(Widget widget)
	{
		return widget.getBounds().intersection(insideParents(widget));
	}

	private static Rectangle insideParents(Widget widget)
	{
		Rectangle area = new Rectangle(Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MAX_VALUE, Integer.MAX_VALUE);
		for (Widget parent = widget.getParent(); parent != null && !area.isEmpty(); parent = parent.getParent())
		{
			Rectangle outer = parent.getBounds();
			if (outer != null && outer.width > 0)
			{
				area = area.intersection(outer);
			}
		}
		return area;
	}
}
