package com.cluehelper.ui;

import java.awt.Color;
import java.awt.image.BufferedImage;

public final class Icons
{
	/**
	 * A clue scroll in OSRS item-sprite style: hard pixels, black outline, a lit edge and a shadow
	 * per material. One pixel per character: 'K' outline, 'p'/'q'/'r' parchment lit, base and
	 * shadow, 'l' ink, '.' transparent.
	 */
	private static final String[] LOGO = {
		"................",
		".KKKKKKKKKKKKKK.",
		"KppppppppppppqqK",
		"KqqqqqqqqqqqqrrK",
		".KKKKKKKKKKKKKK.",
		"..KpqqqqqqqqqK..",
		"..KplllllllqqK..",
		"..KpqqqqqqqqqK..",
		"..KpllllllqqqK..",
		"..KpqqqqqqqqqK..",
		"..KplllllllqqK..",
		"..KpqqqqqqqqrK..",
		".KKKKKKKKKKKKKK.",
		"KppppppppppppqqK",
		"KqqqqqqqqqqqqrrK",
		".KKKKKKKKKKKKKK.",
	};

	public static BufferedImage logo()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < LOGO.length; y++)
		{
			for (int x = 0; x < LOGO[y].length(); x++)
			{
				Color c = logoColor(LOGO[y].charAt(x));
				if (c != null)
				{
					image.setRGB(x, y, c.getRGB());
				}
			}
		}
		return image;
	}

	private static Color logoColor(char ch)
	{
		switch (ch)
		{
			case 'K':
				return Color.BLACK;
			case 'p':
				return new Color(244, 228, 184);
			case 'q':
				return new Color(218, 194, 142);
			case 'r':
				return new Color(176, 150, 100);
			case 'l':
				return new Color(110, 80, 44);
			default:
				return null;
		}
	}

	private Icons()
	{
	}
}
