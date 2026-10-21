package com.cluehelper.ui;

import java.awt.Color;
import java.awt.image.BufferedImage;

public final class Icons
{
	private static final Color PARCHMENT_LIT = new Color(244, 228, 184);
	private static final Color PARCHMENT = new Color(218, 194, 142);
	private static final Color PARCHMENT_SHADOW = new Color(176, 150, 100);
	private static final Color INK = new Color(110, 80, 44);
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
				return PARCHMENT_LIT;
			case 'q':
				return PARCHMENT;
			case 'r':
				return PARCHMENT_SHADOW;
			case 'l':
				return INK;
			default:
				return null;
		}
	}

	private Icons()
	{
	}
}
