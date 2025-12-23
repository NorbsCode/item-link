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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.OverheadTextChanged;
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
 * This plugin automatically detects item names in chat messages and highlights them
 * with colored text based on their GE value (rarity). Just type naturally!
 *
 * Examples:
 *   - "I just got an Abyssal whip!" -> "I just got an [Abyssal whip]!"
 *   - "Selling Dragon scimitar 100k" -> "Selling [Dragon scimitar] 100k"
 *
 * Items are colored by their GE value (WoW-style rarity colors).
 * Hover over linked items to see detailed stats and prices.
 * Other players with this plugin will also see the highlighted items.
 */
@PluginDescriptor(
	name = "Item Link",
	description = "Automatically highlights item names in chat with rarity colors and tooltips",
	tags = {"item", "link", "chat", "share", "wow", "highlight", "tooltip", "price"}
)
@Slf4j
public class ItemLinkPlugin extends Plugin
{
	// Rarity colors (WoW-style)
	private static final String COLOR_COMMON = "ffffff";      // White
	private static final String COLOR_UNCOMMON = "1eff00";    // Green
	private static final String COLOR_RARE = "0070dd";        // Blue
	private static final String COLOR_EPIC = "a335ee";        // Purple
	private static final String COLOR_LEGENDARY = "ff8000";   // Orange
	private static final String COLOR_DEFAULT = "ff8000";     // Orange (default)

	// Default chat colors for restoration after item highlight
	// These are used in transparent chatbox mode
	private static final String COLOR_PUBLIC_CHAT = "9090ff";     // Light blue (public chat)
	private static final String COLOR_FC_CHAT = "ef5050";         // Salmon red (friends/clan chat)

	// Minimum item name length to avoid false positives
	private static final int MIN_ITEM_NAME_LENGTH = 5;

	// Map of lowercase item name -> item ID
	private final Map<String, Integer> itemNameToId = new HashMap<>();

	// List of item names sorted by length (longest first) for matching
	private final List<String> sortedItemNames = new ArrayList<>();

	// Set of all single-word item names for O(1) lookup
	private final Set<String> singleWordItems = new HashSet<>();

	// Map of first word -> list of multi-word item names starting with that word (sorted by length, longest first)
	private final Map<String, List<String>> multiWordItemsByFirstWord = new HashMap<>();

	private boolean itemsLoaded = false;

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

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			loadItemNames();
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("ItemLink plugin stopped");
		overlayManager.remove(itemLinkOverlay);
		itemLinkOverlay.clearRecentItems();
		itemNameToId.clear();
		sortedItemNames.clear();
		singleWordItems.clear();
		multiWordItemsByFirstWord.clear();
		itemsLoaded = false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN && !itemsLoaded)
		{
			loadItemNames();
		}
	}

	// Number of items to process per chunk to avoid blocking the game
	private static final int ITEMS_PER_CHUNK = 2000;
	private static final int MAX_ITEM_ID = 30000;

	/**
	 * Load all item names into the lookup map.
	 * Uses chunked loading to avoid blocking the game client.
	 */
	private void loadItemNames()
	{
		itemNameToId.clear();
		sortedItemNames.clear();
		singleWordItems.clear();
		multiWordItemsByFirstWord.clear();

		// Start loading from the first chunk
		loadItemChunk(0);
	}

	/**
	 * Load a chunk of items starting at the given ID.
	 */
	private void loadItemChunk(final int startId)
	{
		clientThread.invokeLater(() ->
		{
			int endId = Math.min(startId + ITEMS_PER_CHUNK, MAX_ITEM_ID);

			for (int itemId = startId; itemId < endId; itemId++)
			{
				try
				{
					ItemComposition itemComp = itemManager.getItemComposition(itemId);
					if (itemComp == null)
					{
						continue;
					}

					String name = itemComp.getName();
					if (name == null || name.equals("null") || name.isEmpty())
					{
						continue;
					}

					// Skip very short names to avoid false positives
					if (name.length() < MIN_ITEM_NAME_LENGTH)
					{
						continue;
					}

					// Skip noted items and placeholders
					if (itemComp.getNote() != -1 || itemComp.getPlaceholderTemplateId() != -1)
					{
						continue;
					}

					String lowerName = name.toLowerCase();

					// Only store first occurrence (base item)
					if (!itemNameToId.containsKey(lowerName))
					{
						itemNameToId.put(lowerName, itemId);
					}
				}
				catch (Exception e)
				{
					// Skip problematic items
				}
			}

			if (endId < MAX_ITEM_ID)
			{
				// Schedule next chunk
				loadItemChunk(endId);
			}
			else
			{
				// All chunks loaded, finalize
				finalizeItemLoading();
			}
		});
	}

	/**
	 * Finalize item loading by building optimized lookup structures.
	 */
	private void finalizeItemLoading()
	{
		// Sort item names by length (longest first) for greedy matching
		sortedItemNames.addAll(itemNameToId.keySet());
		sortedItemNames.sort((a, b) -> Integer.compare(b.length(), a.length()));

		// Build optimized lookup structures
		for (String itemName : sortedItemNames)
		{
			int spaceIndex = itemName.indexOf(' ');
			if (spaceIndex == -1)
			{
				// Single-word item
				singleWordItems.add(itemName);
			}
			else
			{
				// Multi-word item - index by first word
				String firstWord = itemName.substring(0, spaceIndex);
				multiWordItemsByFirstWord
					.computeIfAbsent(firstWord, k -> new ArrayList<>())
					.add(itemName);
			}
		}

		itemsLoaded = true;
		log.info("Loaded {} item names for Item Link ({} single-word, {} multi-word prefixes)",
			itemNameToId.size(), singleWordItems.size(), multiWordItemsByFirstWord.size());
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!itemsLoaded)
		{
			return;
		}

		// Only process player chat messages, not system/game messages
		ChatMessageType type = event.getType();
		switch (type)
		{
			case PUBLICCHAT:
			case MODCHAT:
			case PRIVATECHAT:
			case PRIVATECHATOUT:
			case FRIENDSCHAT:
			case CLAN_CHAT:
			case CLAN_GUEST_CHAT:
			case CLAN_GIM_CHAT:
			case AUTOTYPER:
			case MODAUTOTYPER:
			case TRADEREQ:
			case CHALREQ_TRADE:
				// These are player messages - process them
				break;
			default:
				// Skip system messages, examine text, NPC dialogue, etc.
				return;
		}

		String message = event.getMessage();
		if (message == null || message.isEmpty())
		{
			return;
		}

		String processed = highlightItemNames(message);
		if (!processed.equals(message))
		{
			event.getMessageNode().setValue(processed);
		}
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		if (!itemsLoaded)
		{
			return;
		}

		Actor actor = event.getActor();
		if (!(actor instanceof Player))
		{
			return;
		}

		String text = event.getOverheadText();
		if (text == null || text.isEmpty())
		{
			return;
		}

		String formatted = highlightItemNames(text);
		if (!formatted.equals(text))
		{
			actor.setOverheadText(formatted);
		}
	}

	/**
	 * Scan message for item names and highlight them.
	 * Uses optimized word-boundary scanning with HashSet lookups.
	 */
	private String highlightItemNames(String message)
	{
		String lowerMessage = message.toLowerCase();
		StringBuilder result = new StringBuilder();

		int i = 0;
		while (i < message.length())
		{
			// Only check at word boundaries (start of message or after non-letter/digit)
			boolean atWordBoundary = (i == 0 || !Character.isLetterOrDigit(lowerMessage.charAt(i - 1)));

			if (!atWordBoundary || !Character.isLetterOrDigit(lowerMessage.charAt(i)))
			{
				result.append(message.charAt(i));
				i++;
				continue;
			}

			// Extract the current word
			int wordEnd = i;
			while (wordEnd < lowerMessage.length() && Character.isLetterOrDigit(lowerMessage.charAt(wordEnd)))
			{
				wordEnd++;
			}
			String currentWord = lowerMessage.substring(i, wordEnd);

			String matchedItemName = null;

			// First, check for multi-word items starting with this word (longest first)
			List<String> multiWordCandidates = multiWordItemsByFirstWord.get(currentWord);
			if (multiWordCandidates != null)
			{
				for (String candidate : multiWordCandidates)
				{
					if (i + candidate.length() > lowerMessage.length())
					{
						continue;
					}

					// Check if full item name matches
					if (!lowerMessage.regionMatches(i, candidate, 0, candidate.length()))
					{
						continue;
					}

					// Check end boundary
					int endPos = i + candidate.length();
					if (endPos < lowerMessage.length() && Character.isLetterOrDigit(lowerMessage.charAt(endPos)))
					{
						continue;
					}

					matchedItemName = candidate;
					break; // Multi-word candidates are already sorted longest-first
				}
			}

			// If no multi-word match, check for single-word item
			if (matchedItemName == null && singleWordItems.contains(currentWord))
			{
				matchedItemName = currentWord;
			}

			if (matchedItemName != null)
			{
				// Found a match! Get original case from message
				String originalName = message.substring(i, i + matchedItemName.length());
				int itemId = itemNameToId.get(matchedItemName);
				String color = getItemColor(itemId);

				result.append(String.format("<col=%s>[%s]</col><col=%s>", color, originalName, COLOR_PUBLIC_CHAT));

				// Add to overlay for tooltip
				itemLinkOverlay.addRecentItem(itemId, 1);

				i += matchedItemName.length();
			}
			else
			{
				result.append(message.charAt(i));
				i++;
			}
		}

		return result.toString();
	}

	/**
	 * Determines the color for an item based on its GE value.
	 */
	private String getItemColor(int itemId)
	{
		if (!config.colorByRarity())
		{
			return COLOR_DEFAULT;
		}

		int gePrice = itemManager.getItemPrice(itemId);

		if (gePrice >= 10000000)
		{
			return COLOR_LEGENDARY;
		}
		else if (gePrice >= 1000000)
		{
			return COLOR_EPIC;
		}
		else if (gePrice >= 100000)
		{
			return COLOR_RARE;
		}
		else if (gePrice >= 10000)
		{
			return COLOR_UNCOMMON;
		}
		else
		{
			return COLOR_COMMON;
		}
	}
}
