package com.cluehelper.transport;

import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;

@Value
public class AvailabilitySet
{
	public static final AvailabilitySet EMPTY = new AvailabilitySet(0, List.of(), Long.MAX_VALUE);

	long snapshotVersion;
	List<RowAvailability> rows;
	/** The epoch minute a cooldown clears and the set must be judged again; {@link Long#MAX_VALUE} if none. */
	long validUntilMinute;

	public List<RowAvailability> available()
	{
		return rows.stream().filter(RowAvailability::isAvailable).collect(Collectors.toList());
	}
}
