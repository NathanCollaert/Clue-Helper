package com.cluehelper.route;

/**
 * The eight steps in the order the game's own walking tries them: W, E, S, N, SW, SE, NW, NE, straight ones first.
 * A search that ties in this order finds the tiles the game walks.
 */
final class Directions
{
	static final int COUNT = 8;
	static final int STRAIGHT = 4;
	static final int[] DX = {-1, 1, 0, 0, -1, 1, -1, 1};
	static final int[] DY = {0, 0, -1, 1, -1, -1, 1, 1};

	private Directions()
	{
	}
}
