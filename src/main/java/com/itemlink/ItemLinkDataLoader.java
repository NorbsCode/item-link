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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Loads item data (examine text, etc.) from the OSRS Wiki API.
 */
@Slf4j
@Singleton
public class ItemLinkDataLoader
{
	// OSRS Wiki Real-Time Prices API - mapping endpoint has examine text
	private static final String WIKI_API_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping";
	private static final String USER_AGENT = "ItemLink-RuneLite-Plugin";
	private static final String CACHE_FILENAME = "itemlink-mapping.json";
	private static final long CACHE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L; // 7 days

	private final Map<Integer, WikiItem> itemsById = new HashMap<>();
	private final Gson gson = new Gson();
	private boolean loaded = false;

	@Inject
	public ItemLinkDataLoader()
	{
	}

	/**
	 * Load item data from OSRS Wiki API (with local caching).
	 */
	public void loadItems()
	{
		if (loaded)
		{
			return;
		}

		// Try loading from cache first
		File cacheFile = new File(RuneLite.RUNELITE_DIR, CACHE_FILENAME);
		if (cacheFile.exists())
		{
			long age = System.currentTimeMillis() - cacheFile.lastModified();
			if (age < CACHE_MAX_AGE_MS)
			{
				if (loadFromCache(cacheFile))
				{
					log.info("Loaded {} items from cache (age: {} hours)",
						itemsById.size(), age / (60 * 60 * 1000));
					return;
				}
			}
			else
			{
				log.info("Cache expired, fetching fresh data from Wiki API");
			}
		}

		// Fetch from API
		if (loadFromApi())
		{
			// Save to cache
			saveToCache(cacheFile);
		}
	}

	private boolean loadFromCache(File cacheFile)
	{
		try (FileReader reader = new FileReader(cacheFile, StandardCharsets.UTF_8))
		{
			Type listType = new TypeToken<List<WikiItem>>(){}.getType();
			List<WikiItem> items = gson.fromJson(reader, listType);

			if (items == null || items.isEmpty())
			{
				return false;
			}

			for (WikiItem item : items)
			{
				if (item.getId() > 0)
				{
					itemsById.put(item.getId(), item);
				}
			}

			loaded = true;
			return true;
		}
		catch (Exception e)
		{
			log.warn("Failed to load from cache: {}", e.getMessage());
			return false;
		}
	}

	private boolean loadFromApi()
	{
		log.info("Fetching item data from OSRS Wiki API...");

		try
		{
			URL url = new URL(WIKI_API_URL);
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("User-Agent", USER_AGENT);
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(30000);

			int responseCode = conn.getResponseCode();
			if (responseCode != 200)
			{
				log.error("Wiki API returned status {}", responseCode);
				return false;
			}

			try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
			{
				Type listType = new TypeToken<List<WikiItem>>(){}.getType();
				List<WikiItem> items = gson.fromJson(reader, listType);

				if (items == null || items.isEmpty())
				{
					log.error("Wiki API returned empty data");
					return false;
				}

				int withExamine = 0;
				for (WikiItem item : items)
				{
					if (item.getId() > 0)
					{
						itemsById.put(item.getId(), item);
						if (item.getExamine() != null && !item.getExamine().isEmpty())
						{
							withExamine++;
						}
					}
				}

				loaded = true;
				log.info("Loaded {} items from Wiki API ({} with examine text)",
					itemsById.size(), withExamine);

				// Debug sample
				WikiItem whip = itemsById.get(4151);
				if (whip != null)
				{
					log.info("Sample: Abyssal whip examine='{}'", whip.getExamine());
				}

				return true;
			}
		}
		catch (Exception e)
		{
			log.error("Failed to fetch from Wiki API: {}", e.getMessage());
			return false;
		}
	}

	private void saveToCache(File cacheFile)
	{
		try (FileWriter writer = new FileWriter(cacheFile, StandardCharsets.UTF_8))
		{
			gson.toJson(itemsById.values(), writer);
			log.debug("Saved {} items to cache", itemsById.size());
		}
		catch (Exception e)
		{
			log.warn("Failed to save cache: {}", e.getMessage());
		}
	}

	/**
	 * Get item data by item ID.
	 */
	public WikiItem getItemById(int itemId)
	{
		return itemsById.get(itemId);
	}

	/**
	 * Check if data has been loaded.
	 */
	public boolean isLoaded()
	{
		return loaded;
	}

	/**
	 * Item data from Wiki API.
	 */
	public static class WikiItem
	{
		private int id;
		private String name;
		private String examine;
		private int value;
		private boolean members;
		private Integer lowalch;
		private Integer highalch;
		private Integer limit;
		private String icon;

		public int getId() { return id; }
		public String getName() { return name; }
		public String getExamine() { return examine; }
		public int getValue() { return value; }
		public boolean isMembers() { return members; }
		public Integer getLowalch() { return lowalch; }
		public Integer getHighalch() { return highalch; }
		public Integer getLimit() { return limit; }
		public String getIcon() { return icon; }
	}
}

