package com.cluehelper.dev;

import com.cluehelper.player.Charge;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.transport.AvailabilitySet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

/** Client thread only. Logs what each availability judgement gained and lost. */
@Slf4j
final class PlayerDump
{
	static final String TAG = "[CH-PLAYER]";

	private final Client client;
	private final PlayerModel model;
	private Set<String> lastAvailable = new TreeSet<>();

	PlayerDump(Client client, PlayerModel model)
	{
		this.client = client;
		this.model = model;
	}

	void onJudged(AvailabilitySet carried)
	{
		PlayerSnapshot s = model.getSnapshot();
		Set<String> available = carried.available().stream().map(r -> r.getRow().getName()).collect(Collectors.toCollection(TreeSet::new));
		Set<String> gained = new TreeSet<>(available);
		gained.removeAll(lastAvailable);
		Set<String> lost = new TreeSet<>(lastAvailable);
		lost.removeAll(available);
		lastAvailable = available;
		StringBuilder line = new StringBuilder(TAG).append(" t").append(client.getTickCount())
			.append(" snapshot v").append(s == null ? 0 : s.getVersion())
			.append(" A_carried ").append(available.size()).append('/').append(carried.getRows().size());
		if (s != null)
		{
			line.append(" pouch ").append(s.isPouchHeld() ? names(s.getPouch()) : "not held")
				.append(" charges ").append(charges(s));
		}
		if (!gained.isEmpty())
		{
			line.append(" +").append(gained);
		}
		if (!lost.isEmpty())
		{
			line.append(" -").append(lost);
		}
		log.info(line.toString());
	}

	private String charges(PlayerSnapshot s)
	{
		return s.getCharges().entrySet().stream()
			.map(e -> name(e.getKey()) + "=" + e.getValue().getCharges() + " " + source(e.getValue()))
			.collect(Collectors.joining(", ", "[", "]"));
	}

	private static String source(Charge charge)
	{
		return charge.getSource().name().toLowerCase().replace('_', ' ');
	}

	private String names(Map<Integer, Integer> items)
	{
		return items.entrySet().stream().map(e -> e.getValue() + " " + name(e.getKey())).collect(Collectors.joining(", ", "[", "]"));
	}

	private String name(int id)
	{
		return client.getItemDefinition(id).getName();
	}
}
