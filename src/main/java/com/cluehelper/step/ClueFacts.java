package com.cluehelper.step;

import java.util.Locale;

/**
 * Field names match the "fields" object of the replay fixtures and of {@code ::ch-step}'s fixture
 * lines, so recorded clues replay through the same key rule.
 */
public final class ClueFacts
{
	static final int NO_ITEM = -1;

	private String type;
	private int itemId = NO_ITEM;
	private String text;
	private int[] tile;
	private int widgetGroupId = -1;
	private String tier;
	private String npc;
	private String challengeType;
	private String rawChallenge;

	private ClueFacts()
	{
	}

	static ClueFacts of(String type)
	{
		ClueFacts facts = new ClueFacts();
		facts.type = type;
		return facts;
	}

	ClueFacts itemId(int itemId)
	{
		this.itemId = itemId;
		return this;
	}

	ClueFacts text(String text)
	{
		this.text = text;
		return this;
	}

	ClueFacts tile(int x, int y, int plane)
	{
		this.tile = new int[]{x, y, plane};
		return this;
	}

	ClueFacts widgetGroupId(int widgetGroupId)
	{
		this.widgetGroupId = widgetGroupId;
		return this;
	}

	ClueFacts tier(String tier)
	{
		this.tier = tier;
		return this;
	}

	ClueFacts npc(String npc)
	{
		this.npc = npc;
		return this;
	}

	ClueFacts challenge(String challengeType, String rawChallenge)
	{
		this.challengeType = challengeType;
		this.rawChallenge = rawChallenge;
		return this;
	}

	public String getType()
	{
		return type;
	}

	public int getItemId()
	{
		return itemId;
	}

	public String getText()
	{
		return text;
	}

	public int[] getTile()
	{
		return tile == null ? null : tile.clone();
	}

	public int getWidgetGroupId()
	{
		return widgetGroupId;
	}

	public String getTier()
	{
		return tier;
	}

	public String getNpc()
	{
		return npc;
	}

	public String getChallengeType()
	{
		return challengeType;
	}

	public String getRawChallenge()
	{
		return rawChallenge;
	}

	public static String normalise(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.replaceAll("<[^>]*>", " ")
			.replaceAll("\\s+", " ")
			.trim()
			.toLowerCase(Locale.ROOT);
	}
}
