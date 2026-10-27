package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.link.ClueLink;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.route.Plan;
import com.cluehelper.route.PlanInput;
import com.cluehelper.route.PlanRecording;
import com.cluehelper.route.Route;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.step.Step;
import com.cluehelper.transport.AvailabilityService;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.RowAvailability;
import com.google.gson.Gson;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigItemDescriptor;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.LinkBrowser;

/**
 * Everything about the current step and its routes, copied to the clipboard for a GitHub issue: a readable summary,
 * then the plan's whole input, compressed, which the developer tools plan again offline.
 */
@Singleton
public class RouteReport
{
	public static final String REPLAY_START = "--- replay data ---";
	public static final String REPLAY_END = "--- end of replay data ---";
	private static final String NEW_ISSUE = "https://github.com/NathanCollaert/Clue-Helper/issues/new?template=route-report.yml";
	private static final int NEARBY_TILES = 64;
	private static final int MOST_NEARBY = 40;
	private static final String HAVE = "have";
	private static final String IN_THE_BANK = "in the bank";

	private final ClueLink clueLink;
	private final RoutePlanner routes;
	private final AvailabilityService availability;
	private final ConfigManager configManager;
	private final ClueHelperConfig config;
	private final ChatMessageManager chat;
	private final Gson gson;
	private volatile PlanInput lastInput;
	private volatile Plan lastPlan;
	private Runnable unregister;

	@Inject
	RouteReport(ClueLink clueLink, RoutePlanner routes, AvailabilityService availability, ConfigManager configManager,
		ClueHelperConfig config, ChatMessageManager chat, Gson gson)
	{
		this.clueLink = clueLink;
		this.routes = routes;
		this.availability = availability;
		this.configManager = configManager;
		this.config = config;
		this.chat = chat;
		this.gson = gson;
	}

	public void start()
	{
		unregister = routes.addInputListener((plan, input) ->
		{
			lastPlan = plan;
			lastInput = input;
		});
	}

	public void stop()
	{
		unregister.run();
		lastPlan = null;
		lastInput = null;
	}

	/** Client thread: copies the report and opens the issue form it is pasted into. */
	public void send()
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(write()), null);
		chat.queue(QueuedMessage.builder().type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage("Clue Helper: route report copied. Paste it into the GitHub issue form that just opened.")
			.build());
		LinkBrowser.browse(NEW_ISSUE);
	}

	private String write()
	{
		StringBuilder out = new StringBuilder();
		out.append("Clue Helper route report, RuneLite ").append(RuneLiteProperties.getVersion())
			.append(", ").append(Instant.now()).append('\n');
		Step step = travelStep();
		writeStep(out, step);
		writePlayer(out);
		writeSettings(out);
		writeRoutes(out, routes.getPlan());
		if (step != null)
		{
			writeNearbyTeleports(out, step);
		}
		writeReplay(out, step);
		return out.toString();
	}

	private Step travelStep()
	{
		PlanInput input = lastInput;
		return input != null && input.getStep() != null ? input.getStep() : clueLink.getCurrentStep();
	}

	private static void writeStep(StringBuilder out, Step step)
	{
		out.append("\nStep\n");
		if (step == null)
		{
			out.append("  none\n");
			return;
		}
		out.append("  ").append(step.getTier()).append(' ').append(step.getType()).append(": ").append(step.getText()).append('\n');
		out.append("  key: ").append(step.getKey()).append('\n');
		out.append("  targets: ").append(tiles(step.getTargets())).append('\n');
	}

	private void writePlayer(StringBuilder out)
	{
		PlanInput input = lastInput;
		out.append("\nPlayer\n");
		if (input == null)
		{
			out.append("  no plan yet\n");
			return;
		}
		PlayerSnapshot player = input.getPlayer();
		out.append("  at: ").append(tile(input.getStart().getTile())).append(" (").append(input.getStart().getKind()).append(")\n");
		out.append("  account: ").append(player.getAccountType())
			.append(player.isMembersWorld() ? ", members world" : ", free-to-play world").append('\n');
		out.append("  spellbook: ").append(player.getSpellbook() == null ? "unknown" : player.getSpellbook().getBook()).append('\n');
		out.append("  bank counted: ").append(input.getBanked() == null ? "no" : "yes").append('\n');
	}

	private void writeSettings(StringBuilder out)
	{
		out.append("\nSettings\n");
		for (ConfigItemDescriptor item : configManager.getConfigDescriptor(config).getItems())
		{
			String key = item.getItem().keyName();
			out.append("  ").append(item.getItem().name()).append(": ")
				.append(configManager.getConfiguration(ClueHelperConfig.GROUP, key)).append('\n');
		}
	}

	private void writeRoutes(StringBuilder out, Plan plan)
	{
		out.append("\nRoutes\n");
		if (plan.getNote() != null)
		{
			out.append("  note: ").append(plan.getNote()).append('\n');
		}
		route(out, "followed", routes.getCommitted());
		route(out, "best", plan.getBest());
		route(out, "best without the bank", plan.getDirect());
		route(out, "best without a spellbook change", plan.getSameBook());
		route(out, "faster but within the margin", plan.getFasterUnqualified());
		plan.getAlternatives().forEach(alternative -> route(out, "alternative", alternative));
		plan.getCandidates().forEach(candidate -> route(out, "candidate " + candidate.getCategory(), candidate.getRoute()));
		plan.getRequirementNotes().forEach(note -> out.append("  requirement: ").append(note).append('\n'));
	}

	private static void route(StringBuilder out, String label, Route route)
	{
		if (route != null)
		{
			out.append("  ").append(label).append(": ").append(route.describe()).append(" (").append(route.getTicks()).append(" ticks")
				.append(route.isBanked() ? ", banks" : "").append(")\n");
		}
	}

	/** Each teleport landing near the step, and whether the plugin thinks the player has it, or why not. */
	private void writeNearbyTeleports(StringBuilder out, Step step)
	{
		out.append("\nTeleports landing within ").append(NEARBY_TILES).append(" tiles of the step\n");
		Map<CatalogRow, RowAvailability> banked = new HashMap<>();
		availability.getBanked().getRows().forEach(row -> banked.put(row.getRow(), row));
		Map<String, String> statusByTeleport = new LinkedHashMap<>();
		availability.getCarried().getRows().stream()
			.filter(row -> row.getRow().getDestination() != null)
			.filter(row -> distance(row.getRow().getDestination(), step.getTargets()) <= NEARBY_TILES)
			.sorted(Comparator.comparingInt(row -> distance(row.getRow().getDestination(), step.getTargets())))
			.forEach(row -> statusByTeleport.merge(row.getRow().getName() + " (" + row.getRow().getCategory() + ")",
				status(row, banked.get(row.getRow())), RouteReport::better));
		statusByTeleport.entrySet().stream().limit(MOST_NEARBY)
			.forEach(teleport -> out.append("  ").append(teleport.getKey()).append(": ").append(teleport.getValue()).append('\n'));
	}

	private static String status(RowAvailability carried, RowAvailability banked)
	{
		if (carried.isAvailable())
		{
			return HAVE;
		}
		if (banked != null && banked.isAvailable())
		{
			return IN_THE_BANK;
		}
		return "missing: " + String.join("; ", carried.getWhyNot());
	}

	/** A teleport with several rows (one per unlock state, say) is as good as its best row; else its first reason stands. */
	private static String better(String first, String second)
	{
		if (first.equals(HAVE) || second.equals(HAVE))
		{
			return HAVE;
		}
		return second.equals(IN_THE_BANK) ? IN_THE_BANK : first;
	}

	private static int distance(WorldPoint to, List<WorldPoint> targets)
	{
		return targets.stream().mapToInt(target -> target.distanceTo2D(to)).min().orElse(Integer.MAX_VALUE);
	}

	private void writeReplay(StringBuilder out, Step step)
	{
		Plan plan = lastPlan;
		PlanInput input = lastInput;
		if (plan == null || input == null || step == null)
		{
			return;
		}
		String json = gson.toJson(PlanRecording.forReport(plan, input));
		out.append('\n').append(REPLAY_START).append('\n').append(gzipBase64(json)).append('\n').append(REPLAY_END).append('\n');
	}

	private static String gzipBase64(String text)
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(bytes))
		{
			gzip.write(text.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("compressing the route report's replay data", e);
		}
		return Base64.getMimeEncoder().encodeToString(bytes.toByteArray());
	}

	private static String tiles(List<WorldPoint> tiles)
	{
		StringBuilder out = new StringBuilder();
		for (WorldPoint tile : tiles)
		{
			out.append(out.length() == 0 ? "" : ", ").append(tile(tile));
		}
		return out.toString();
	}

	private static String tile(WorldPoint tile)
	{
		return tile == null ? "unknown" : tile.getX() + "," + tile.getY() + "," + tile.getPlane();
	}
}
