package com.cluehelper.ui;

import com.cluehelper.link.ClueLink;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepIndex;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The challenge's answer beside the game's "Enter amount:" prompt, in the menu-line blue. Display only, never typed:
 * Nathan's approved exception to repeating core.
 */
public class ChallengeAnswerOverlay extends Overlay
{
	private static final Color BLUE = new Color(0x40D0FF);
	private static final String PROMPT = "Enter amount";
	private static final int GAP = 8;

	private final Client client;
	private final ClueLink clueLink;

	@Inject
	ChallengeAnswerOverlay(Client client, ClueLink clueLink)
	{
		this.client = client;
		this.clueLink = clueLink;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Integer answer = answer(clueLink.getCurrentStep());
		Widget prompt = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		if (answer == null || prompt == null || prompt.isHidden() || prompt.getText() == null || !prompt.getText().contains(PROMPT))
		{
			return null;
		}
		Widget typed = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		Rectangle at = typed != null && !typed.isHidden() ? typed.getBounds() : prompt.getBounds();
		graphics.setFont(FontManager.getRunescapeBoldFont());
		String text = "Answer: " + answer;
		int width = graphics.getFontMetrics().stringWidth(text);
		int x = at.x + at.width / 2 + graphics.getFontMetrics().stringWidth(typed == null || typed.getText() == null ? "" : typed.getText()) / 2 + GAP;
		int y = at.y + at.height / 2 + graphics.getFontMetrics().getAscent() / 2;
		graphics.setColor(Color.BLACK);
		graphics.drawString(text, x + 1, y + 1);
		graphics.setColor(BLUE);
		graphics.drawString(text, x, y);
		return new Dimension(width, at.height);
	}

	private static Integer answer(Step step)
	{
		StepIndex.Entry data = step == null ? null : step.getData();
		return data == null || data.getChallenge() == null ? null : data.getChallenge().getAnswer();
	}
}
