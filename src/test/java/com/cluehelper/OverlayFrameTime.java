package com.cluehelper;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * {@code ::ch-frametime}: draws each of Clue Helper's scene overlays again, for {@value #FRAMES} frames, into an off-screen
 * image the canvas's size, and logs the mean time each took. The real overlays keep drawing on screen as usual.
 */
@Slf4j
final class OverlayFrameTime extends Overlay
{
	static final String COMMAND = "ch-frametime";
	private static final String TAG = "[CH-FRAMETIME]";
	private static final int FRAMES = 300;
	private static final double NANOS_PER_MICRO = 1_000.0;

	private final Client client;
	private final List<Overlay> timed;
	private final Map<String, Long> nanos = new LinkedHashMap<>();
	private BufferedImage canvas;
	private int framesLeft;

	OverlayFrameTime(Client client, ClueHelperPlugin plugin)
	{
		this.client = client;
		this.timed = sceneOverlays(plugin);
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/** Client thread. */
	void start()
	{
		nanos.clear();
		framesLeft = FRAMES;
		log.info("{} timing {} over {} frames", TAG, timed.stream().map(o -> o.getClass().getSimpleName()).collect(Collectors.toList()), FRAMES);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (framesLeft <= 0)
		{
			return null;
		}
		if (canvas == null || canvas.getWidth() != client.getCanvasWidth() || canvas.getHeight() != client.getCanvasHeight())
		{
			canvas = new BufferedImage(client.getCanvasWidth(), client.getCanvasHeight(), BufferedImage.TYPE_INT_ARGB);
		}
		for (Overlay overlay : timed)
		{
			Graphics2D offScreen = canvas.createGraphics();
			long start = System.nanoTime();
			overlay.render(offScreen);
			nanos.merge(overlay.getClass().getSimpleName(), System.nanoTime() - start, Long::sum);
			offScreen.dispose();
		}
		if (--framesLeft == 0)
		{
			nanos.forEach((name, total) -> log.info("{} {} {} µs per frame", TAG, name, String.format("%.1f", total / NANOS_PER_MICRO / FRAMES)));
			long all = nanos.values().stream().mapToLong(Long::longValue).sum();
			log.info("{} all {} µs per frame", TAG, String.format("%.1f", all / NANOS_PER_MICRO / FRAMES));
		}
		return null;
	}

	private static List<Overlay> sceneOverlays(ClueHelperPlugin plugin)
	{
		try
		{
			Method overlays = ClueHelperPlugin.class.getDeclaredMethod("overlays");
			overlays.setAccessible(true);
			return Arrays.stream((Overlay[]) overlays.invoke(plugin))
				.filter(o -> o.getPosition() == OverlayPosition.DYNAMIC && o.getLayer() == OverlayLayer.ABOVE_SCENE)
				.collect(Collectors.toList());
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("can't read the plugin's overlays", e);
		}
	}
}
