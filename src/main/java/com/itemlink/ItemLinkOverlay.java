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

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.QuantityFormatter;

public class ItemLinkOverlay extends Overlay
{
	private static final int MAX_CACHED_ITEMS = 50;

	// Pattern to detect colored item names in chat: <col=XXXXXX>[ItemName]</col> or <col=XXXXXX>[ItemName x123]</col>
	private static final Pattern COLORED_ITEM_PATTERN = Pattern.compile("<col=[0-9a-fA-F]+>\\[([^\\]]+?)(?:\\s+x(\\d+))?\\]</col>");

	// Rarity colors (WoW-style)
	private static final String COLOR_COMMON = "ffffff";      // White
	private static final String COLOR_UNCOMMON = "1eff00";    // Green
	private static final String COLOR_RARE = "0070dd";        // Blue
	private static final String COLOR_EPIC = "a335ee";        // Purple
	private static final String COLOR_LEGENDARY = "ff8000";   // Orange

	// UI colors
	private static final String COLOR_LABEL = "999999";
	private static final String COLOR_VALUE_POSITIVE = "00ff00";
	private static final String COLOR_VALUE_NEGATIVE = "ff0000";
	private static final String COLOR_VALUE_NEUTRAL = "ffff00";
	private static final String COLOR_GOLD = "ffd700";
	private static final String COLOR_ALCH = "ff9040";
	private static final String COLOR_SEPARATOR = "444444";

	// Cache of item info by item ID
	private final Map<Integer, ItemLinkInfo> recentItems = new LinkedHashMap<Integer, ItemLinkInfo>()
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, ItemLinkInfo> eldest)
		{
			return size() > MAX_CACHED_ITEMS;
		}
	};

	// Cache of item info by item name (lowercase for case-insensitive lookup)
	private final Map<String, ItemLinkInfo> itemsByName = new LinkedHashMap<String, ItemLinkInfo>()
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, ItemLinkInfo> eldest)
		{
			return size() > MAX_CACHED_ITEMS;
		}
	};

	private final Client client;
	private final ItemLinkConfig config;
	private final ItemManager itemManager;
	private final TooltipManager tooltipManager;

	@Inject
	private ItemLinkOverlay(Client client, ItemLinkPlugin plugin, ItemLinkConfig config,
							ItemManager itemManager, TooltipManager tooltipManager)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		this.tooltipManager = tooltipManager;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (itemsByName.isEmpty())
		{
			return null;
		}

		Point mousePos = client.getMouseCanvasPosition();
		if (mousePos == null)
		{
			return null;
		}

		// Check multiple possible chat widgets
		Widget hoveredChatLine = findHoveredChatLine(mousePos);
		if (hoveredChatLine == null)
		{
			return null;
		}

		String text = hoveredChatLine.getText();
		if (text == null || !text.contains("<col="))
		{
			return null;
		}

		// Find colored item names in the text
		List<ItemLinkInfo> foundItems = new ArrayList<>();
		Matcher matcher = COLORED_ITEM_PATTERN.matcher(text);

		while (matcher.find())
		{
			String itemName = matcher.group(1);
			String quantityStr = matcher.group(2);
			int quantity = quantityStr != null ? Integer.parseInt(quantityStr) : 1;

			// Lookup using lowercase for case-insensitive matching
			ItemLinkInfo info = itemsByName.get(itemName.toLowerCase());
			if (info != null)
			{
				// Create copy with the correct quantity from this specific link
				foundItems.add(copyWithQuantity(info, quantity));
			}
		}

		if (!foundItems.isEmpty())
		{
			showItemsTooltipFromList(foundItems);
		}

		return null;
	}

	/**
	 * Find the chat line widget the mouse is hovering over.
	 */
	private Widget findHoveredChatLine(Point mousePos)
	{
		Widget found;

		// CHATBOX group is 162
		// Chat lines are individual widgets: LINE0 starts at child 58 (0x3a), goes up to LINE46+ at child 104+
		// SCROLLAREA is child 57 (0x39) - contains the chat lines for opaque chatbox
		// CHATDISPLAY is child 55 (0x37) - for transparent chatbox

		// First, directly check individual chat line widgets (most reliable)
		// LINE0 = child 58, LINE1 = child 59, etc.
		for (int lineId = 58; lineId < 150; lineId++)
		{
			Widget lineWidget = client.getWidget(162, lineId);
			if (lineWidget == null)
			{
				continue;
			}

			String text = lineWidget.getText();
			// Must check for actual item link pattern: <col=XXXXXX>[ItemName]</col>
			// Not just any [bracket] with color (like friends chat channel names)
			if (text != null && COLORED_ITEM_PATTERN.matcher(text).find())
			{
				Rectangle bounds = lineWidget.getBounds();
				if (bounds != null && bounds.contains(mousePos.getX(), mousePos.getY()))
				{
					return lineWidget;
				}
			}

			// Also check dynamic children of each line widget
			Widget[] children = lineWidget.getDynamicChildren();
			if (children != null)
			{
				for (Widget child : children)
				{
					if (child == null) continue;
					String childText = child.getText();
					if (childText != null && COLORED_ITEM_PATTERN.matcher(childText).find())
					{
						Rectangle childBounds = child.getBounds();
						if (childBounds != null && childBounds.contains(mousePos.getX(), mousePos.getY()))
						{
							return child;
						}
					}
				}
			}
		}

		// Try the SCROLLAREA (child 57) and its children
		Widget scrollArea = client.getWidget(162, 57);
		if (scrollArea != null)
		{
			found = checkWidgetChildren(scrollArea, mousePos);
			if (found != null) return found;
		}

		// Try the CHATDISPLAY (child 55) for transparent chatbox
		Widget chatDisplay = client.getWidget(162, 55);
		if (chatDisplay != null)
		{
			found = checkWidgetChildren(chatDisplay, mousePos);
			if (found != null) return found;
		}

		// Last resort: search all children of chatbox groups
		int[] chatGroups = {162, 163, 7};
		for (int groupId : chatGroups)
		{
			for (int childId = 0; childId < 200; childId++)
			{
				Widget widget = client.getWidget(groupId, childId);
				if (widget == null)
				{
					continue;
				}

				found = recursiveWidgetSearch(widget, mousePos, true);
				if (found != null) return found;
			}
		}

		return null;
	}

	/**
	 * Recursively search widget and its children for hover.
	 */
	private Widget recursiveWidgetSearch(Widget widget, Point mousePos, boolean searchAllChildren)
	{
		if (widget == null)
		{
			return null;
		}

		// Check this widget - only if it has actual item link pattern and mouse is within bounds
		String text = widget.getText();
		if (text != null && !text.isEmpty() && COLORED_ITEM_PATTERN.matcher(text).find())
		{
			Rectangle bounds = widget.getBounds();
			if (bounds != null && bounds.contains(mousePos.getX(), mousePos.getY()))
			{
				return widget;
			}
		}

		// Always search children - the item link text might be in a nested widget
		Widget[] children = widget.getDynamicChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				Widget found = recursiveWidgetSearch(child, mousePos, searchAllChildren);
				if (found != null) return found;
			}
		}

		children = widget.getStaticChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				Widget found = recursiveWidgetSearch(child, mousePos, searchAllChildren);
				if (found != null) return found;
			}
		}

		children = widget.getNestedChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				Widget found = recursiveWidgetSearch(child, mousePos, searchAllChildren);
				if (found != null) return found;
			}
		}

		return null;
	}

	/**
	 * Check widget's children for mouse hover.
	 */
	private Widget checkWidgetChildren(Widget parent, Point mousePos)
	{
		if (parent == null || parent.isHidden())
		{
			return null;
		}

		Widget[][] childArrays = {
			parent.getDynamicChildren(),
			parent.getStaticChildren(),
			parent.getNestedChildren()
		};

		for (Widget[] children : childArrays)
		{
			if (children != null)
			{
				for (Widget child : children)
				{
					if (child != null && !child.isHidden())
					{
						Rectangle bounds = child.getBounds();
						if (bounds != null && bounds.contains(mousePos.getX(), mousePos.getY()))
						{
							String text = child.getText();
							if (text != null && COLORED_ITEM_PATTERN.matcher(text).find())
							{
								return child;
							}

							Widget found = checkWidgetChildren(child, mousePos);
							if (found != null)
							{
								return found;
							}
						}
					}
				}
			}
		}

		return null;
	}

	public void addRecentItem(int itemId, int quantity)
	{
		ItemComposition itemComp = itemManager.getItemComposition(itemId);
		if (itemComp == null || itemComp.getName() == null)
		{
			return;
		}

		String itemName = itemComp.getName();
		if (itemName.equals("null"))
		{
			return;
		}

		ItemLinkInfo info = new ItemLinkInfo();
		info.itemId = itemId;
		info.quantity = quantity;
		info.name = itemName;
		info.timestamp = System.currentTimeMillis();
		info.gePrice = itemManager.getItemPrice(itemId);
		info.haPrice = itemComp.getHaPrice();

		ItemStats stats = itemManager.getItemStats(itemId);
		if (stats != null)
		{
			info.weight = stats.getWeight();
			info.isEquipable = stats.isEquipable();
			info.geLimit = stats.getGeLimit();
			info.equipmentStats = stats.getEquipment();
		}

		recentItems.put(itemId, info);
		itemsByName.put(itemName.toLowerCase(), info);
	}

	private String formatStat(String name, int value)
	{
		return formatStat(name, value, "");
	}

	private String formatStat(String name, int value, String suffix)
	{
		String color = value > 0 ? COLOR_VALUE_POSITIVE : (value < 0 ? COLOR_VALUE_NEGATIVE : COLOR_LABEL);
		String sign = value > 0 ? "+" : "";
		return "<col=" + COLOR_LABEL + ">" + name + ":</col> <col=" + color + ">" + sign + value + suffix + "</col></br>";
	}

	private boolean hasAttackBonuses(ItemEquipmentStats eq)
	{
		return eq.getAstab() != 0 || eq.getAslash() != 0 || eq.getAcrush() != 0 ||
			   eq.getAmagic() != 0 || eq.getArange() != 0;
	}

	private boolean hasDefenceBonuses(ItemEquipmentStats eq)
	{
		return eq.getDstab() != 0 || eq.getDslash() != 0 || eq.getDcrush() != 0 ||
			   eq.getDmagic() != 0 || eq.getDrange() != 0;
	}

	private boolean hasOtherBonuses(ItemEquipmentStats eq)
	{
		return eq.getStr() != 0 || eq.getRstr() != 0 || eq.getMdmg() != 0 || eq.getPrayer() != 0;
	}

	private String getSlotName(int slot)
	{
		switch (slot)
		{
			case 0: return "Head";
			case 1: return "Cape";
			case 2: return "Neck";
			case 3: return "Weapon";
			case 4: return "Body";
			case 5: return "Shield";
			case 7: return "Legs";
			case 9: return "Gloves";
			case 10: return "Boots";
			case 12: return "Ring";
			case 13: return "Ammo";
			default: return "Equipment";
		}
	}

	private String getRarityColor(int gePrice)
	{
		if (gePrice >= 10000000) return COLOR_LEGENDARY;
		if (gePrice >= 1000000) return COLOR_EPIC;
		if (gePrice >= 100000) return COLOR_RARE;
		if (gePrice >= 10000) return COLOR_UNCOMMON;
		return COLOR_COMMON;
	}

	public void clearRecentItems()
	{
		recentItems.clear();
		itemsByName.clear();
	}

	private void showItemsTooltipFromList(List<ItemLinkInfo> items)
	{
		StringBuilder tooltip = new StringBuilder();
		boolean first = true;

		for (ItemLinkInfo info : items)
		{
			if (!first)
			{
				tooltip.append("</br><col=").append(COLOR_SEPARATOR).append(">═════════════════════</col></br>");
			}
			first = false;

			appendItemTooltip(tooltip, info);
		}

		if (tooltip.length() > 0)
		{
			tooltipManager.add(new Tooltip(tooltip.toString()));
		}
	}

	private ItemLinkInfo copyWithQuantity(ItemLinkInfo original, int newQuantity)
	{
		ItemLinkInfo copy = new ItemLinkInfo();
		copy.itemId = original.itemId;
		copy.quantity = newQuantity;
		copy.name = original.name;
		copy.timestamp = original.timestamp;
		copy.gePrice = original.gePrice;
		copy.haPrice = original.haPrice;
		copy.weight = original.weight;
		copy.isEquipable = original.isEquipable;
		copy.geLimit = original.geLimit;
		copy.equipmentStats = original.equipmentStats;
		return copy;
	}

	private void appendItemTooltip(StringBuilder tooltip, ItemLinkInfo info)
	{
		String rarityColor = getRarityColor(info.gePrice);

		// ITEM NAME
		tooltip.append("<col=").append(rarityColor).append(">").append(info.name).append("</col>");
		if (info.quantity > 1)
		{
			tooltip.append(" <col=").append(COLOR_LABEL).append(">x").append(QuantityFormatter.formatNumber(info.quantity)).append("</col>");
		}
		tooltip.append("</br>");

		// Separator
		tooltip.append("<col=").append(COLOR_SEPARATOR).append(">─────────────────────</col></br>");

		// PRICE INFORMATION
		boolean hasPriceInfo = false;

		if (info.gePrice > 0)
		{
			hasPriceInfo = true;
			long totalGe = (long) info.gePrice * info.quantity;
			tooltip.append("<col=").append(COLOR_GOLD).append(">GE Price:</col> ");
			tooltip.append("<col=").append(COLOR_VALUE_NEUTRAL).append(">");
			tooltip.append(QuantityFormatter.quantityToStackSize(totalGe)).append(" gp");
			if (info.quantity > 1)
			{
				tooltip.append(" (").append(QuantityFormatter.quantityToStackSize(info.gePrice)).append(" ea)");
			}
			tooltip.append("</col></br>");

			if (info.geLimit > 0)
			{
				tooltip.append("<col=").append(COLOR_LABEL).append(">Buy Limit:</col> ");
				tooltip.append("<col=").append(COLOR_VALUE_NEUTRAL).append(">");
				tooltip.append(QuantityFormatter.formatNumber(info.geLimit));
				tooltip.append("</col></br>");
			}
		}

		if (info.haPrice > 0)
		{
			hasPriceInfo = true;
			long totalHa = (long) info.haPrice * info.quantity;
			tooltip.append("<col=").append(COLOR_ALCH).append(">HA Value:</col> ");
			tooltip.append("<col=").append(COLOR_VALUE_NEUTRAL).append(">");
			tooltip.append(QuantityFormatter.quantityToStackSize(totalHa)).append(" gp");
			if (info.quantity > 1)
			{
				tooltip.append(" (").append(QuantityFormatter.quantityToStackSize(info.haPrice)).append(" ea)");
			}
			tooltip.append("</col></br>");
		}

		// EQUIPMENT STATS
		if (info.isEquipable && info.equipmentStats != null)
		{
			ItemEquipmentStats eq = info.equipmentStats;

			if (hasPriceInfo)
			{
				tooltip.append("<col=").append(COLOR_SEPARATOR).append(">─────────────────────</col></br>");
			}

			String slotName = getSlotName(eq.getSlot());
			tooltip.append("<col=").append(COLOR_LABEL).append(">Slot:</col> ");
			tooltip.append("<col=ffffff>").append(slotName);
			if (eq.isTwoHanded())
			{
				tooltip.append(" (Two-handed)");
			}
			tooltip.append("</col></br>");

			if (hasAttackBonuses(eq))
			{
				tooltip.append("<col=").append(COLOR_LABEL).append(">Attack Bonuses</col></br>");
				if (eq.getAstab() != 0) tooltip.append(formatStat("  Stab", eq.getAstab()));
				if (eq.getAslash() != 0) tooltip.append(formatStat("  Slash", eq.getAslash()));
				if (eq.getAcrush() != 0) tooltip.append(formatStat("  Crush", eq.getAcrush()));
				if (eq.getAmagic() != 0) tooltip.append(formatStat("  Magic", eq.getAmagic()));
				if (eq.getArange() != 0) tooltip.append(formatStat("  Ranged", eq.getArange()));
			}

			if (hasDefenceBonuses(eq))
			{
				tooltip.append("<col=").append(COLOR_LABEL).append(">Defence Bonuses</col></br>");
				if (eq.getDstab() != 0) tooltip.append(formatStat("  Stab", eq.getDstab()));
				if (eq.getDslash() != 0) tooltip.append(formatStat("  Slash", eq.getDslash()));
				if (eq.getDcrush() != 0) tooltip.append(formatStat("  Crush", eq.getDcrush()));
				if (eq.getDmagic() != 0) tooltip.append(formatStat("  Magic", eq.getDmagic()));
				if (eq.getDrange() != 0) tooltip.append(formatStat("  Ranged", eq.getDrange()));
			}

			if (hasOtherBonuses(eq))
			{
				tooltip.append("<col=").append(COLOR_LABEL).append(">Other Bonuses</col></br>");
				if (eq.getStr() != 0) tooltip.append(formatStat("  Melee Str", eq.getStr()));
				if (eq.getRstr() != 0) tooltip.append(formatStat("  Ranged Str", eq.getRstr()));
				if (eq.getMdmg() != 0) tooltip.append(formatStat("  Magic Dmg", (int) eq.getMdmg(), "%"));
				if (eq.getPrayer() != 0) tooltip.append(formatStat("  Prayer", eq.getPrayer()));
			}

			if (eq.getAspeed() > 0)
			{
				tooltip.append("<col=").append(COLOR_LABEL).append(">Attack Speed:</col> ");
				tooltip.append("<col=ffffff>").append(eq.getAspeed()).append("</col></br>");
			}
		}

		// WEIGHT
		if (info.weight != 0)
		{
			tooltip.append("<col=").append(COLOR_LABEL).append(">Weight:</col> ");
			String weightColor = info.weight < 0 ? COLOR_VALUE_POSITIVE : (info.weight > 0 ? COLOR_VALUE_NEUTRAL : COLOR_LABEL);
			tooltip.append("<col=").append(weightColor).append(">");
			tooltip.append(String.format("%.2f kg", info.weight));
			tooltip.append("</col>");
		}
	}

	private static class ItemLinkInfo
	{
		int itemId;
		int quantity;
		String name;
		int gePrice;
		int haPrice;
		long timestamp;
		boolean isEquipable;
		double weight;
		int geLimit;
		ItemEquipmentStats equipmentStats;
	}
}
