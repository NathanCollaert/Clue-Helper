package com.cluehelper.player;

import java.util.*;
import javax.inject.*;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.DBTableID;

/**
 * Client thread only. Counts the music tracks the Music cape still needs, read from the game's Music table the way
 * Shortest Path's ClientPlayerStateSource does (BSD-2, see NOTICE). The cape refuses to teleport while one is locked,
 * and a game update that adds a track locks it again.
 */
@Singleton
public class MusicTracks
{
	/** get_music_varp: leaves the value of the unlock varp at a one-based index on the int stack. */
	private static final int GET_MUSIC_VARP = 7305;
	private static final int NOT_RELEASED = 0;
	private static final int NO_HOLIDAY = -1;
	/** "You have unlocked a new music track: Harmony." */
	private static final String UNLOCKED_TEXT = "unlocked a new music track";

	@Inject
	Client client;

	public static boolean tellsOfAnUnlock(ChatMessage message)
	{
		return message.getType() == ChatMessageType.GAMEMESSAGE && message.getMessage().contains(UNLOCKED_TEXT);
	}

	int locked()
	{
		Map<Integer, Integer> unlockVarps = new HashMap<>();
		int locked = 0;
		for (int track : client.getDBTableRows(DBTableID.Music.ID))
		{
			if (countsForTheCape(track) && !isUnlocked(track, unlockVarps))
			{
				locked++;
			}
		}
		return locked;
	}

	private boolean countsForTheCape(int track)
	{
		return field(track, DBTableID.Music.COL_RELEASE_TYPE, 0) != NOT_RELEASED
			&& field(track, DBTableID.Music.COL_HIDDEN, 0) == 0
			&& field(track, DBTableID.Music.COL_HOLIDAY, 0) == NO_HOLIDAY;
	}

	private boolean isUnlocked(int track, Map<Integer, Integer> unlockVarps)
	{
		if (field(track, DBTableID.Music.COL_AUTOMATIC_UNLOCK, 0) != 0)
		{
			return true;
		}
		int varpIndex = field(track, DBTableID.Music.COL_VARIABLE, 0);
		int bit = field(track, DBTableID.Music.COL_VARIABLE, 1);
		if (varpIndex < 1 || bit < 0 || bit >= Integer.SIZE)
		{
			return false;
		}
		return (unlockVarps.computeIfAbsent(varpIndex, this::unlockVarp) & 1 << bit) != 0;
	}

	private int unlockVarp(int index)
	{
		client.runScript(GET_MUSIC_VARP, index);
		return client.getIntStack()[0];
	}

	private int field(int row, int column, int tupleIndex)
	{
		return (Integer) client.getDBTableField(row, column, tupleIndex)[0];
	}
}
