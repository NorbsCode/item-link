/*
 * Copyright (c) 2025, LordStrange
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.itemlink;

import com.google.inject.Provides;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * ItemLink Plugin - WoW-style item linking for OSRS chat
 *
 * This plugin allows players to Shift+right-click items in their inventory, equipment,
 * or bank and select "Link in chat" to insert a linkable item reference into the chat.
 * Other players using this plugin will see the item rendered with colored text and
 * can hover over the link to see detailed item information including stats and prices.
 *
 * Features:
 * - Shift+right-click any item to link it in chat
 * - WoW-style rarity colors based on GE value
 * - Hover tooltips showing GE price, HA value, equipment stats, and more
 * - Works in public chat, friends chat, clan chat, and private messages
 * - Formatted display while typing and in overhead text
 */
@PluginDescriptor(
	name = "Item Link",
	description = "Link items in chat like WoW - Shift+right-click items to share them with detailed tooltips",
	tags = {"item", "link", "chat", "share", "wow", "tooltip", "price"}
)
@Slf4j
public class ItemLinkPlugin extends Plugin
{
	private static final String LINK_IN_CHAT = "Link in chat";

	// Token pattern: [[rlitem:ITEM_ID:QUANTITY]]
	private static final Pattern ITEM_LINK_PATTERN = Pattern.compile("\\[\\[rlitem:(\\d+):(\\d+)\\]\\]");

	// Rarity colors (WoW-style)
	private static final String COLOR_COMMON = "ffffff";      // White - under 10K
	private static final String COLOR_UNCOMMON = "1eff00";    // Green - 10K+
	private static final String COLOR_RARE = "0070dd";        // Blue - 100K+
	private static final String COLOR_EPIC = "a335ee";        // Purple - 1M+
	private static final String COLOR_LEGENDARY = "ff8000";   // Orange - 10M+
	private static final String COLOR_DEFAULT = "ff8000";     // Orange (default)

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ItemLinkConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ItemLinkOverlay itemLinkOverlay;

	@Provides
	ItemLinkConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ItemLinkConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		log.info("ItemLink plugin started");
		overlayManager.add(itemLinkOverlay);
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("ItemLink plugin stopped");
		overlayManager.remove(itemLinkOverlay);
		itemLinkOverlay.clearRecentItems();
	}

	/**
	 * Handles the ScriptCallbackEvent to intercept and modify chat messages
	 * before they are rendered.
	 */
	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!"chatFilterCheck".equals(event.getEventName()))
		{
			return;
		}

		Object[] objectStack = client.getObjectStack();
		int objectStackSize = client.getObjectStackSize();

		String message = (String) objectStack[objectStackSize - 1];

		if (message == null || !message.contains("[[rlitem:"))
		{
			return;
		}

		String processedMessage = processItemLinks(message);
		objectStack[objectStackSize - 1] = processedMessage;

		// Add to recent items for tooltip display
		ItemLinkData[] links = extractItemLinks(message);
		for (ItemLinkData link : links)
		{
			itemLinkOverlay.addRecentItem(link.getItemId(), link.getQuantity());
		}
	}

	/**
	 * Format item links in the chatbox input while typing.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		Widget chatboxInput = client.getWidget(WidgetInfo.CHATBOX_INPUT);
		if (chatboxInput == null || chatboxInput.isHidden())
		{
			return;
		}

		// Get the actual typed text (contains raw tokens)
		String typedText = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
		if (typedText == null || !typedText.contains("[[rlitem:"))
		{
			return;
		}

		// Format the display text with colored item names
		String displayText = processItemLinksForDisplay(typedText);

		// Get the current widget text to check the prefix (e.g., "Player Name: ")
		String currentWidgetText = chatboxInput.getText();
		if (currentWidgetText == null)
		{
			return;
		}

		// Find the prefix (everything before the typed text starts)
		// The widget text format is typically "PlayerName: typed text*"
		int prefixEnd = currentWidgetText.indexOf(typedText.isEmpty() ? "*" : typedText.substring(0, Math.min(1, typedText.length())));
		String prefix = "";
		if (prefixEnd > 0)
		{
			// Try to find where the actual message starts by looking for common patterns
			int colonPos = currentWidgetText.indexOf(": ");
			if (colonPos > 0 && colonPos < 50)
			{
				prefix = currentWidgetText.substring(0, colonPos + 2);
			}
		}

		// Update the widget display with formatted text
		chatboxInput.setText(prefix + displayText + "*");
	}

	/**
	 * Format item links in overhead text when player speaks.
	 */
	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		Actor actor = event.getActor();

		// Only process for players (not NPCs)
		if (!(actor instanceof Player))
		{
			return;
		}

		String text = event.getOverheadText();
		if (text == null || !text.contains("[[rlitem:"))
		{
			return;
		}

		// Format the overhead text with colored item names
		String formattedText = processItemLinks(text);
		actor.setOverheadText(formattedText);
	}

	/**
	 * Process item links for display purposes (removes the fallback text too).
	 */
	private String processItemLinksForDisplay(String message)
	{
		Matcher matcher = ITEM_LINK_PATTERN.matcher(message);
		StringBuffer result = new StringBuffer();

		while (matcher.find())
		{
			try
			{
				int itemId = Integer.parseInt(matcher.group(1));
				int quantity = Integer.parseInt(matcher.group(2));

				if (itemId <= 0 || itemId > 50000)
				{
					continue;
				}

				ItemComposition itemComp = itemManager.getItemComposition(itemId);
				String itemName = itemComp.getName();

				if (itemName == null || itemName.equals("null"))
				{
					continue;
				}

				String color = getItemColor(itemId, itemComp);
				String quantityStr = quantity > 1 ? " x" + quantity : "";

				String replacement = String.format("<col=%s>[%s%s]</col>",
					color, itemName, quantityStr);

				matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
			}
			catch (NumberFormatException e)
			{
				log.debug("Failed to parse item link numbers", e);
			}
		}

		matcher.appendTail(result);

		String processed = result.toString();
		// Also clean up the fallback text (item name before the link)
		processed = cleanupFallbackText(processed);

		return processed;
	}

	/**
	 * Process all item link tokens in a message and replace them with formatted text.
	 */
	private String processItemLinks(String message)
	{
		Matcher matcher = ITEM_LINK_PATTERN.matcher(message);
		StringBuffer result = new StringBuffer();

		while (matcher.find())
		{
			try
			{
				int itemId = Integer.parseInt(matcher.group(1));
				int quantity = Integer.parseInt(matcher.group(2));

				if (itemId <= 0 || itemId > 50000)
				{
					continue;
				}

				ItemComposition itemComp = itemManager.getItemComposition(itemId);
				String itemName = itemComp.getName();

				if (itemName == null || itemName.equals("null"))
				{
					continue;
				}

				String color = getItemColor(itemId, itemComp);
				String quantityStr = quantity > 1 ? " x" + quantity : "";

				String replacement = String.format("<col=%s>[%s%s]</col>",
					color, itemName, quantityStr);

				matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
			}
			catch (NumberFormatException e)
			{
				log.debug("Failed to parse item link numbers", e);
			}
		}

		matcher.appendTail(result);

		String processed = result.toString();
		processed = cleanupFallbackText(processed);

		return processed;
	}

	/**
	 * Removes the human-readable fallback text that precedes item links.
	 * Matches pattern like "Item Name x1 " right before the colored link.
	 */
	private String cleanupFallbackText(String message)
	{
		// Match: "ItemName xN " immediately before "<col=...>[ItemName"
		// This is more specific to avoid removing usernames
		Pattern cleanupPattern = Pattern.compile("([A-Z][\\w\\s'()-]+?)\\s+x(\\d+)\\s+(<col=[^>]+>\\[\\1)");
		Matcher matcher = cleanupPattern.matcher(message);

		StringBuffer result = new StringBuffer();
		while (matcher.find())
		{
			matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(3)));
		}
		matcher.appendTail(result);

		return result.toString();
	}

	/**
	 * Determines the color for an item based on its GE value.
	 */
	private String getItemColor(int itemId, ItemComposition itemComp)
	{
		if (!config.colorByRarity())
		{
			return COLOR_DEFAULT;
		}

		// Use GE price for rarity
		int gePrice = itemManager.getItemPrice(itemId);

		if (gePrice >= 10000000)      // 10M+
		{
			return COLOR_LEGENDARY;
		}
		else if (gePrice >= 1000000)  // 1M+
		{
			return COLOR_EPIC;
		}
		else if (gePrice >= 100000)   // 100K+
		{
			return COLOR_RARE;
		}
		else if (gePrice >= 10000)    // 10K+
		{
			return COLOR_UNCOMMON;
		}
		else
		{
			return COLOR_COMMON;
		}
	}

	/**
	 * Opens the chatbox input if it's not already open.
	 */
	private void openChatboxIfNeeded()
	{
		clientThread.invokeLater(() ->
		{
			// Check if chatbox is already open for typing
			Widget chatboxInput = client.getWidget(WidgetInfo.CHATBOX_INPUT);
			if (chatboxInput == null || chatboxInput.isHidden())
			{
				// Try to open the chatbox by running the appropriate script
				// This simulates pressing Enter to open chat
				client.runScript(ScriptID.CHAT_PROMPT_INIT);
			}
		});
	}

	/**
	 * Inserts an item link token into the current chatbox input.
	 */
	private void insertItemLink(int itemId, String itemName, int quantity)
	{
		clientThread.invokeLater(() ->
		{
			// Get current chatbox text
			String currentText = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
			if (currentText == null)
			{
				currentText = "";
			}

			// Build the item link token
			String quantityStr = quantity > 1 ? " x" + quantity : " x1";
			String itemLink = String.format("%s%s [[rlitem:%d:%d]]",
				itemName, quantityStr, itemId, quantity);

			// Append to current text
			String newText;
			if (currentText.isEmpty())
			{
				newText = itemLink;
			}
			else
			{
				if (!currentText.endsWith(" "))
				{
					newText = currentText + " " + itemLink;
				}
				else
				{
					newText = currentText + itemLink;
				}
			}

			// Set the new chatbox text
			client.setVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT, newText);

			// Update the chatbox widget to reflect the change
			Widget chatboxInput = client.getWidget(WidgetInfo.CHATBOX_INPUT);
			if (chatboxInput != null)
			{
				chatboxInput.setText(newText + "*");
			}
		});
	}

	/**
	 * Extracts item link data from a message.
	 */
	public ItemLinkData[] extractItemLinks(String message)
	{
		if (message == null || !message.contains("[[rlitem:"))
		{
			return new ItemLinkData[0];
		}

		Matcher matcher = ITEM_LINK_PATTERN.matcher(message);
		java.util.List<ItemLinkData> links = new java.util.ArrayList<>();

		while (matcher.find())
		{
			try
			{
				int itemId = Integer.parseInt(matcher.group(1));
				int quantity = Integer.parseInt(matcher.group(2));

				if (itemId > 0 && itemId < 50000)
				{
					links.add(new ItemLinkData(itemId, quantity, matcher.start(), matcher.end()));
				}
			}
			catch (NumberFormatException e)
			{
				// Skip malformed tokens
			}
		}

		return links.toArray(new ItemLinkData[0]);
	}

	/**
	 * Adds the "Link in chat" menu option when Shift is held.
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		// Only add menu option when shift is held
		if (!client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}

		int itemId = event.getItemId();
		if (itemId <= 0)
		{
			return;
		}

		// Check if we already added a link option (any link option)
		for (MenuEntry entry : client.getMenu().getMenuEntries())
		{
			if (LINK_IN_CHAT.equals(entry.getOption()))
			{
				return;
			}
		}

		// Get quantity from the widget
		int quantity = 1;
		Widget widget = client.getWidget(event.getActionParam1());
		if (widget != null)
		{
			Widget child = widget.getChild(event.getActionParam0());
			if (child != null && child.getItemQuantity() > 0)
			{
				quantity = child.getItemQuantity();
			}
		}

		final int finalQuantity = quantity;

		// Add our custom menu entry
		client.getMenu().createMenuEntry(-1)
			.setOption(LINK_IN_CHAT)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.setIdentifier(event.getIdentifier())
			.setParam0(event.getActionParam0())
			.setParam1(event.getActionParam1())
			.onClick(e -> handleLinkInChat(itemId, finalQuantity));
	}

	/**
	 * Handles the "Link in chat" menu click.
	 */
	private void handleLinkInChat(int itemId, int quantity)
	{
		if (itemId <= 0)
		{
			return;
		}

		ItemComposition itemComp = itemManager.getItemComposition(itemId);
		String itemName = itemComp.getName();

		if (itemName == null || itemName.equals("null"))
		{
			return;
		}

		if (quantity <= 0)
		{
			quantity = 1;
		}

		// Open chatbox if needed and insert the link
		openChatboxIfNeeded();
		insertItemLink(itemId, itemName, quantity);

		log.debug("Linked item in chat: {} x{} (ID: {})", itemName, quantity, itemId);
	}

	/**
	 * Data class to hold parsed item link information.
	 */
	@lombok.Data
	@lombok.AllArgsConstructor
	public static class ItemLinkData
	{
		private final int itemId;
		private final int quantity;
		private final int startIndex;
		private final int endIndex;
	}
}
