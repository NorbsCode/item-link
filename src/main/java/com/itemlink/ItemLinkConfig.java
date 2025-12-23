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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("itemlink")
public interface ItemLinkConfig extends Config
{
	@ConfigItem(
		keyName = "colorByRarity",
		name = "Color by rarity",
		description = "Color item links based on GE value (WoW-style rarity colors)",
		position = 0
	)
	default boolean colorByRarity()
	{
		return true;
	}

	@ConfigItem(
		keyName = "filteredItems",
		name = "Filtered items",
		description = "Comma-separated list of item names to exclude from linking (e.g., 'Coins, Bones, Ashes')",
		position = 1
	)
	default String filteredItems()
	{
		return "";
	}

	@ConfigItem(
		keyName = "minimumItemValue",
		name = "Minimum item value",
		description = "Only link items worth at least this much GP (0 to disable)",
		position = 2
	)
	default int minimumItemValue()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "maximumItemValue",
		name = "Maximum item value",
		description = "Only link items worth at most this much GP (0 to disable)",
		position = 3
	)
	default int maximumItemValue()
	{
		return 0;
	}
}

