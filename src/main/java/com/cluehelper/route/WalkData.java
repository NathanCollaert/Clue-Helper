package com.cluehelper.route;

import com.google.gson.Gson;
import java.io.IOException;
import lombok.Value;

@Value
public class WalkData
{
	WalkTable table;
	NoTeleportAreas noTeleport;
	WorldMap map;

	public static WalkData load(Gson gson) throws IOException
	{
		return new WalkData(WalkTable.load(gson), NoTeleportAreas.load(), WorldMap.load());
	}
}
