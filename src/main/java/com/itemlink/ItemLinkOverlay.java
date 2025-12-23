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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.Point;
import net.runelite.api.SpriteID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;

@Slf4j
public class ItemLinkOverlay extends Overlay
{
	private static final int MAX_CACHED_ITEMS = 50;
	private static final int MOUSE_MOVE_THRESHOLD = 2;

	private static final Pattern COLORED_ITEM_PATTERN = Pattern.compile("<col=[0-9a-fA-F]+>([^<]+?)(?:\\s+x(\\d+))?</col>");

	// Rarity colors (WoW-style)
	private static final String COLOR_COMMON = "ffffff";
	private static final String COLOR_UNCOMMON = "1eff00";
	private static final String COLOR_RARE = "0070dd";
	private static final String COLOR_EPIC = "a335ee";
	private static final String COLOR_LEGENDARY = "ff8000";

	// UI colors
	private static final String COLOR_LABEL = "999999";
	private static final String COLOR_VALUE_POSITIVE = "00ff00";
	private static final String COLOR_VALUE_NEGATIVE = "ff0000";
	private static final String COLOR_VALUE_NEUTRAL = "ffff00";
	private static final String COLOR_GOLD = "ffd700";
	private static final String COLOR_SEPARATOR = "444444";
	private static final String COLOR_EXAMINE = "aaaaaa";

	// Mouse position caching
	private int lastMouseX = -1;
	private int lastMouseY = -1;
	private int cachedIconX = -1;
	private int cachedIconY = -1;
	private Widget cachedHoveredWidget = null;
	private String cachedWidgetText = null;
	private List<ItemLinkInfo> cachedFoundItems = null;

	private final Map<Integer, ItemLinkInfo> recentItems = new LinkedHashMap<Integer, ItemLinkInfo>()
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, ItemLinkInfo> eldest)
		{
			return size() > MAX_CACHED_ITEMS;
		}
	};

	private final Map<String, ItemLinkInfo> itemsByName = new LinkedHashMap<String, ItemLinkInfo>()
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, ItemLinkInfo> eldest)
		{
			return size() > MAX_CACHED_ITEMS;
		}
	};

	// Tooltip styling
	private static final int TOOLTIP_PADDING = 8;
	private static final int ITEM_ICON_SIZE = 36;
	private static final int STAT_ICON_SIZE = 16;
	private static final int ICON_TEXT_GAP = 4;
	private static final Color TOOLTIP_BACKGROUND = new Color(30, 30, 30, 245);
	private static final Color TOOLTIP_BORDER = new Color(90, 90, 90);
	private static final Color TOOLTIP_BORDER_INNER = new Color(40, 40, 40);

	private final Client client;
	private final ItemLinkConfig config;
	private final ItemManager itemManager;
	private final SpriteManager spriteManager;
	private final ItemLinkDataLoader ItemLinkDataLoader;

	private final Map<String, BufferedImage> spriteCache = new HashMap<>();
	private boolean spritesLoaded = false;

	// Cached tooltips for multiple items (rendered side by side)
	private List<CachedItemTooltip> cachedTooltips = null;

	@Inject
	private ItemLinkOverlay(Client client, ItemLinkPlugin plugin, ItemLinkConfig config,
							ItemManager itemManager, SpriteManager spriteManager,
							ItemLinkDataLoader ItemLinkDataLoader)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		this.ItemLinkDataLoader = ItemLinkDataLoader;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}

	private void loadSprites()
	{
		if (spritesLoaded)
		{
			return;
		}

		loadSprite("high_alch", SpriteID.SPELL_HIGH_LEVEL_ALCHEMY);
		loadSprite("low_alch", SpriteID.SPELL_LOW_LEVEL_ALCHEMY);
		loadSprite("stab", SpriteID.SKILL_ATTACK);
		loadSprite("slash", SpriteID.SKILL_STRENGTH);
		loadSprite("crush", SpriteID.SKILL_DEFENCE);
		loadSprite("attack", SpriteID.SKILL_ATTACK);
		loadSprite("strength", SpriteID.SKILL_STRENGTH);
		loadSprite("defence", SpriteID.SKILL_DEFENCE);
		loadSprite("ranged", SpriteID.SKILL_RANGED);
		loadSprite("magic", SpriteID.SKILL_MAGIC);
		loadSprite("prayer", SpriteID.SKILL_PRAYER);
		loadSprite("weight", SpriteID.EQUIPMENT_WEIGHT);

		spritesLoaded = true;
	}

	private void loadSprite(String name, int spriteId)
	{
		BufferedImage sprite = spriteManager.getSprite(spriteId, 0);
		if (sprite != null)
		{
			spriteCache.put(name, ImageUtil.resizeImage(sprite, STAT_ICON_SIZE, STAT_ICON_SIZE));
		}
	}

	private BufferedImage getSprite(String name)
	{
		return spriteCache.get(name);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		loadSprites();

		if (itemsByName.isEmpty())
		{
			return null;
		}

		Point mousePos = client.getMouseCanvasPosition();
		if (mousePos == null)
		{
			return null;
		}

		int mouseX = mousePos.getX();
		int mouseY = mousePos.getY();

		boolean mouseMovedSignificantly = Math.abs(mouseX - lastMouseX) > MOUSE_MOVE_THRESHOLD ||
										  Math.abs(mouseY - lastMouseY) > MOUSE_MOVE_THRESHOLD;

		if (!mouseMovedSignificantly && cachedTooltips != null && !cachedTooltips.isEmpty())
		{
			if (cachedHoveredWidget != null)
			{
				Rectangle bounds = cachedHoveredWidget.getBounds();
				if (bounds != null && bounds.contains(mouseX, mouseY))
				{
					renderMultipleTooltips(graphics, cachedTooltips, cachedIconX, cachedIconY);
					return null;
				}
			}
			clearTooltipCache();
			return null;
		}

		lastMouseX = mouseX;
		lastMouseY = mouseY;

		Widget hoveredChatLine = findHoveredChatLine(mousePos);
		if (hoveredChatLine == null)
		{
			clearTooltipCache();
			return null;
		}

		String text = hoveredChatLine.getText();
		if (text == null || !text.contains("<col="))
		{
			clearTooltipCache();
			return null;
		}

		if (text.equals(cachedWidgetText) && cachedTooltips != null && !cachedTooltips.isEmpty())
		{
			cachedHoveredWidget = hoveredChatLine;
			renderMultipleTooltips(graphics, cachedTooltips, cachedIconX, cachedIconY);
			return null;
		}

		cachedHoveredWidget = hoveredChatLine;
		cachedWidgetText = text;

		List<ItemLinkInfo> foundItems = new ArrayList<>();
		Matcher matcher = COLORED_ITEM_PATTERN.matcher(text);

		while (matcher.find())
		{
			String itemName = matcher.group(1);
			String quantityStr = matcher.group(2);
			int quantity = quantityStr != null ? Integer.parseInt(quantityStr) : 1;

			ItemLinkInfo info = itemsByName.get(itemName.toLowerCase());
			if (info != null)
			{
				foundItems.add(copyWithQuantity(info, quantity));
			}
		}

		if (!foundItems.isEmpty())
		{
			cachedFoundItems = foundItems;
			cachedTooltips = new ArrayList<>();
			for (ItemLinkInfo info : foundItems)
			{
				CachedItemTooltip tooltip = new CachedItemTooltip();
				tooltip.icon = itemManager.getImage(info.itemId);
				tooltip.lines = buildTooltipLinesForItem(info);
				cachedTooltips.add(tooltip);
			}
			cachedIconX = mouseX;
			cachedIconY = mouseY;
			renderMultipleTooltips(graphics, cachedTooltips, cachedIconX, cachedIconY);
		}
		else
		{
			clearTooltipCache();
		}

		return null;
	}

	/**
	 * Render multiple tooltips side by side horizontally.
	 */
	private void renderMultipleTooltips(Graphics2D graphics, List<CachedItemTooltip> tooltips, int mouseX, int mouseY)
	{
		if (graphics == null || tooltips == null || tooltips.isEmpty())
		{
			return;
		}

		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		Font font = FontManager.getRunescapeSmallFont();
		graphics.setFont(font);
		FontMetrics fm = graphics.getFontMetrics();

		int lineHeight = Math.max(fm.getHeight(), STAT_ICON_SIZE + 2);
		int tooltipGap = 8;

		// Calculate dimensions for each tooltip
		List<int[]> tooltipDimensions = new ArrayList<>();
		int totalWidth = 0;
		int maxHeight = 0;

		for (CachedItemTooltip tooltip : tooltips)
		{
			int textWidth = 0;
			for (TooltipLine line : tooltip.lines)
			{
				int lineWidth = calculateLineWidth(line, fm);
				textWidth = Math.max(textWidth, lineWidth);
			}

			int textHeight = tooltip.lines.size() * lineHeight;
			boolean hasIcon = (tooltip.icon != null);
			int iconAreaWidth = hasIcon ? ITEM_ICON_SIZE + TOOLTIP_PADDING : 0;

			int width = textWidth + iconAreaWidth + (TOOLTIP_PADDING * 2);
			int height = Math.max(hasIcon ? ITEM_ICON_SIZE + TOOLTIP_PADDING : 0, textHeight) + (TOOLTIP_PADDING * 2);

			tooltipDimensions.add(new int[]{width, height});
			totalWidth += width;
			maxHeight = Math.max(maxHeight, height);
		}

		// Add gaps between tooltips
		totalWidth += tooltipGap * (tooltips.size() - 1);

		// Position tooltips near mouse
		int startX = mouseX + 15;
		int startY = mouseY + 15;

		int canvasWidth = client.getCanvasWidth();
		int canvasHeight = client.getCanvasHeight();

		// Check right edge
		if (startX + totalWidth > canvasWidth)
		{
			startX = mouseX - totalWidth - 5;
		}
		// Check left edge - ensure tooltip doesn't go off-screen
		if (startX < 0)
		{
			startX = 2;
		}

		// Check bottom edge
		if (startY + maxHeight > canvasHeight)
		{
			startY = mouseY - maxHeight - 5;
		}
		// Check top edge
		if (startY < 0)
		{
			startY = 2;
		}

		// Draw each tooltip
		int currentX = startX;
		for (int i = 0; i < tooltips.size(); i++)
		{
			CachedItemTooltip tooltip = tooltips.get(i);
			int[] dims = tooltipDimensions.get(i);
			int tooltipWidth = dims[0];
			int tooltipHeight = dims[1];

			renderSingleTooltip(graphics, tooltip.icon, tooltip.lines, currentX, startY, tooltipWidth, tooltipHeight, fm, lineHeight);

			currentX += tooltipWidth + tooltipGap;
		}
	}

	/**
	 * Render a single tooltip at a specific position.
	 */
	private void renderSingleTooltip(Graphics2D graphics, BufferedImage icon, List<TooltipLine> lines,
									 int tooltipX, int tooltipY, int tooltipWidth, int tooltipHeight,
									 FontMetrics fm, int lineHeight)
	{
		// Draw background
		graphics.setColor(TOOLTIP_BACKGROUND);
		graphics.fillRect(tooltipX, tooltipY, tooltipWidth, tooltipHeight);

		// Draw borders
		graphics.setColor(TOOLTIP_BORDER_INNER);
		graphics.drawRect(tooltipX, tooltipY, tooltipWidth - 1, tooltipHeight - 1);
		graphics.setColor(TOOLTIP_BORDER);
		graphics.drawRect(tooltipX + 1, tooltipY + 1, tooltipWidth - 3, tooltipHeight - 3);

		// Draw item icon on the RIGHT side
		if (icon != null)
		{
			int iconX = tooltipX + tooltipWidth - TOOLTIP_PADDING - ITEM_ICON_SIZE;
			int iconY = tooltipY + TOOLTIP_PADDING;

			graphics.setColor(new Color(30, 30, 30));
			graphics.fillRect(iconX - 1, iconY - 1, ITEM_ICON_SIZE + 2, ITEM_ICON_SIZE + 2);
			graphics.setColor(new Color(60, 60, 60));
			graphics.drawRect(iconX - 1, iconY - 1, ITEM_ICON_SIZE + 1, ITEM_ICON_SIZE + 1);

			int imgX = iconX + (ITEM_ICON_SIZE - icon.getWidth()) / 2;
			int imgY = iconY + (ITEM_ICON_SIZE - icon.getHeight()) / 2;
			graphics.drawImage(icon, imgX, imgY, null);
		}

		// Draw text lines
		int textX = tooltipX + TOOLTIP_PADDING;
		int textY = tooltipY + TOOLTIP_PADDING;

		for (TooltipLine line : lines)
		{
			int xOffset = 0;
			int textCenterY = textY + lineHeight / 2;
			int baselineY = textCenterY + (fm.getAscent() - fm.getDescent()) / 2;

			for (TooltipSegment seg : line.segments)
			{
				if (seg.iconName != null)
				{
					BufferedImage statIcon = getSprite(seg.iconName);
					if (statIcon != null)
					{
						int iconY = textY + (lineHeight - STAT_ICON_SIZE) / 2;
						graphics.drawImage(statIcon, textX + xOffset, iconY, null);
						xOffset += STAT_ICON_SIZE + ICON_TEXT_GAP;
					}
				}
				else if (seg.text != null)
				{
					graphics.setColor(seg.color);
					graphics.drawString(seg.text, textX + xOffset, baselineY);
					xOffset += fm.stringWidth(seg.text);
				}
			}
			textY += lineHeight;
		}
	}

	private int calculateLineWidth(TooltipLine line, FontMetrics fm)
	{
		int width = 0;
		for (TooltipSegment seg : line.segments)
		{
			if (seg.iconName != null)
			{
				width += STAT_ICON_SIZE + ICON_TEXT_GAP;
			}
			else if (seg.text != null)
			{
				width += fm.stringWidth(seg.text);
			}
		}
		return width;
	}

	private void clearTooltipCache()
	{
		cachedHoveredWidget = null;
		cachedWidgetText = null;
		cachedFoundItems = null;
		cachedTooltips = null;
		cachedIconX = -1;
		cachedIconY = -1;
	}

	private Widget findHoveredChatLine(Point mousePos)
	{
		int mx = mousePos.getX();
		int my = mousePos.getY();

		Rectangle chatboxBounds = getChatboxVisibleBounds();
		if (chatboxBounds != null && !chatboxBounds.contains(mx, my))
		{
			return null;
		}

		for (int lineId = 58; lineId < 150; lineId++)
		{
			Widget lineWidget = client.getWidget(162, lineId);
			Widget found = checkWidgetForItemLink(lineWidget, mx, my);
			if (found != null) return found;
		}

		Widget scrollArea = client.getWidget(162, 57);
		if (scrollArea != null && !scrollArea.isHidden())
		{
			Widget found = checkChildrenForItemLink(scrollArea, mx, my);
			if (found != null) return found;
		}

		Widget chatDisplay = client.getWidget(162, 55);
		if (chatDisplay != null && !chatDisplay.isHidden())
		{
			Widget found = checkChildrenForItemLink(chatDisplay, mx, my);
			if (found != null) return found;
		}

		return null;
	}

	private Rectangle getChatboxVisibleBounds()
	{
		Widget scrollArea = client.getWidget(162, 57);
		if (scrollArea != null && !scrollArea.isHidden())
		{
			Rectangle bounds = scrollArea.getBounds();
			if (bounds != null)
			{
				return bounds;
			}
		}

		Widget chatDisplay = client.getWidget(162, 55);
		if (chatDisplay != null && !chatDisplay.isHidden())
		{
			Rectangle bounds = chatDisplay.getBounds();
			if (bounds != null)
			{
				return bounds;
			}
		}

		Widget chatbox = client.getWidget(162, 0);
		if (chatbox != null && !chatbox.isHidden())
		{
			return chatbox.getBounds();
		}

		return null;
	}

	private Widget checkWidgetForItemLink(Widget widget, int mx, int my)
	{
		if (widget == null || widget.isHidden())
		{
			return null;
		}

		Rectangle bounds = widget.getBounds();
		if (bounds == null || !bounds.contains(mx, my))
		{
			return null;
		}

		String text = widget.getText();
		if (text != null && text.contains("<col=") && COLORED_ITEM_PATTERN.matcher(text).find())
		{
			return widget;
		}

		Widget[] children = widget.getDynamicChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				Widget found = checkWidgetForItemLink(child, mx, my);
				if (found != null) return found;
			}
		}

		return null;
	}

	private Widget checkChildrenForItemLink(Widget parent, int mx, int my)
	{
		if (parent == null || parent.isHidden())
		{
			return null;
		}

		Widget[] children = parent.getDynamicChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				Widget found = checkWidgetForItemLink(child, mx, my);
				if (found != null) return found;
			}
		}

		children = parent.getStaticChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				Widget found = checkWidgetForItemLink(child, mx, my);
				if (found != null) return found;
			}
		}

		children = parent.getNestedChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				Widget found = checkWidgetForItemLink(child, mx, my);
				if (found != null) return found;
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

		if (ItemLinkDataLoader.isLoaded())
		{
			ItemLinkDataLoader.WikiItem wikiItem = ItemLinkDataLoader.getItemById(itemId);
			if (wikiItem != null)
			{
				info.examine = wikiItem.getExamine();
				info.isMembers = wikiItem.isMembers();
			}
		}

		recentItems.put(itemId, info);
		itemsByName.put(itemName.toLowerCase(), info);
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
		clearTooltipCache();
		lastMouseX = -1;
		lastMouseY = -1;
	}

	private List<TooltipLine> buildTooltipLinesForItem(ItemLinkInfo info)
	{
		List<TooltipLine> lines = new ArrayList<>();
		appendItemTooltipLines(lines, info);
		return lines;
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
		copy.isMembers = original.isMembers;
		copy.geLimit = original.geLimit;
		copy.equipmentStats = original.equipmentStats;
		copy.examine = original.examine;
		return copy;
	}

	private void appendItemTooltipLines(List<TooltipLine> lines, ItemLinkInfo info)
	{
		Color rarityColor = parseColor(getRarityColor(info.gePrice));
		Color labelColor = parseColor(COLOR_LABEL);
		Color valueColor = parseColor(COLOR_VALUE_NEUTRAL);
		Color goldColor = parseColor(COLOR_GOLD);
		Color examineColor = parseColor(COLOR_EXAMINE);
		Color separatorColor = parseColor(COLOR_SEPARATOR);
		Color positiveColor = parseColor(COLOR_VALUE_POSITIVE);
		Color negativeColor = parseColor(COLOR_VALUE_NEGATIVE);
		Color whiteColor = Color.WHITE;

		// ITEM NAME + MEMBERS/UNTRADEABLE INDICATORS
		TooltipLine nameLine = new TooltipLine().add(info.name, rarityColor);
		if (info.quantity > 1)
		{
			nameLine.add(" x" + QuantityFormatter.formatNumber(info.quantity), labelColor);
		}
		if (info.isMembers)
		{
			nameLine.add(" (P2P)", positiveColor);
		}
		// Show untradeable indicator if item has no GE price and no GE limit
		if (info.gePrice <= 0 && info.geLimit <= 0)
		{
			nameLine.add(" (Untradeable)", negativeColor);
		}
		lines.add(nameLine);

		// EXAMINE TEXT
		if (info.examine != null && !info.examine.isEmpty())
		{
			lines.add(new TooltipLine().add("\"" + info.examine + "\"", examineColor));
		}

		// Separator
		lines.add(new TooltipLine().add("â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€", separatorColor));

		// GE PRICE with limit
		if (info.gePrice > 0)
		{
			long totalGe = (long) info.gePrice * info.quantity;
			TooltipLine geLine = new TooltipLine()
				.add("GE: ", goldColor)
				.add(QuantityFormatter.quantityToStackSize(totalGe) + " gp", valueColor);
			if (info.quantity > 1)
			{
				geLine.add(" (" + QuantityFormatter.quantityToStackSize(info.gePrice) + " ea)", valueColor);
			}
			if (info.geLimit > 0)
			{
				geLine.add("  Limit: ", labelColor)
					  .add(QuantityFormatter.formatNumber(info.geLimit), valueColor);
			}
			lines.add(geLine);
		}

		// HIGH ALCH and LOW ALCH with icons
		if (info.haPrice > 0)
		{
			long totalHa = (long) info.haPrice * info.quantity;
			int laPrice = (int) (info.haPrice * 2 / 3);
			long totalLa = (long) laPrice * info.quantity;

			TooltipLine alchLine = new TooltipLine()
				.addIcon("high_alch")
				.add(QuantityFormatter.quantityToStackSize(totalHa) + " gp", valueColor)
				.add("   ", labelColor)
				.addIcon("low_alch")
				.add(QuantityFormatter.quantityToStackSize(totalLa) + " gp", valueColor);
			lines.add(alchLine);
		}

		// EQUIPMENT INFO
		if (info.isEquipable && info.equipmentStats != null)
		{
			ItemEquipmentStats eq = info.equipmentStats;

			// Slot and speed
			String slotName = getSlotName(eq.getSlot());
			TooltipLine slotLine = new TooltipLine()
				.add("Slot: ", labelColor)
				.add(slotName, whiteColor);
			if (eq.isTwoHanded())
			{
				slotLine.add(" (2H)", whiteColor);
			}
			if (eq.getAspeed() > 0)
			{
				slotLine.add("  Speed: ", labelColor).add(String.valueOf(eq.getAspeed()), whiteColor);
			}
			lines.add(slotLine);

			// Attack bonuses
			if (eq.getAstab() != 0 || eq.getAslash() != 0 || eq.getAcrush() != 0 ||
				eq.getAmagic() != 0 || eq.getArange() != 0)
			{
				lines.add(new TooltipLine().add("Atk: ", labelColor)
					.addIcon("stab").add(formatBonus(eq.getAstab()) + " ", getBonusColor(eq.getAstab(), positiveColor, negativeColor, valueColor))
					.addIcon("slash").add(formatBonus(eq.getAslash()) + " ", getBonusColor(eq.getAslash(), positiveColor, negativeColor, valueColor))
					.addIcon("crush").add(formatBonus(eq.getAcrush()) + " ", getBonusColor(eq.getAcrush(), positiveColor, negativeColor, valueColor))
					.addIcon("magic").add(formatBonus(eq.getAmagic()) + " ", getBonusColor(eq.getAmagic(), positiveColor, negativeColor, valueColor))
					.addIcon("ranged").add(formatBonus(eq.getArange()), getBonusColor(eq.getArange(), positiveColor, negativeColor, valueColor)));
			}

			// Defence bonuses
			if (eq.getDstab() != 0 || eq.getDslash() != 0 || eq.getDcrush() != 0 ||
				eq.getDmagic() != 0 || eq.getDrange() != 0)
			{
				lines.add(new TooltipLine().add("Def: ", labelColor)
					.addIcon("stab").add(formatBonus(eq.getDstab()) + " ", getBonusColor(eq.getDstab(), positiveColor, negativeColor, valueColor))
					.addIcon("slash").add(formatBonus(eq.getDslash()) + " ", getBonusColor(eq.getDslash(), positiveColor, negativeColor, valueColor))
					.addIcon("crush").add(formatBonus(eq.getDcrush()) + " ", getBonusColor(eq.getDcrush(), positiveColor, negativeColor, valueColor))
					.addIcon("magic").add(formatBonus(eq.getDmagic()) + " ", getBonusColor(eq.getDmagic(), positiveColor, negativeColor, valueColor))
					.addIcon("ranged").add(formatBonus(eq.getDrange()), getBonusColor(eq.getDrange(), positiveColor, negativeColor, valueColor)));
			}

			// Other bonuses
			TooltipLine otherLine = new TooltipLine();
			otherLine.add("Other: ", labelColor);
			boolean hasOther = false;

			if (eq.getStr() != 0)
			{
				otherLine.addIcon("strength").add(formatBonus(eq.getStr()) + " ", getBonusColor(eq.getStr(), positiveColor, negativeColor, valueColor));
				hasOther = true;
			}
			if (eq.getRstr() != 0)
			{
				otherLine.addIcon("ranged").add(formatBonus(eq.getRstr()) + " ", getBonusColor(eq.getRstr(), positiveColor, negativeColor, valueColor));
				hasOther = true;
			}
			if (eq.getMdmg() != 0)
			{
				otherLine.addIcon("magic").add(formatBonus((int) eq.getMdmg()) + "% ", getBonusColor((int) eq.getMdmg(), positiveColor, negativeColor, valueColor));
				hasOther = true;
			}
			if (eq.getPrayer() != 0)
			{
				otherLine.addIcon("prayer").add(formatBonus(eq.getPrayer()), getBonusColor(eq.getPrayer(), positiveColor, negativeColor, valueColor));
				hasOther = true;
			}
			if (hasOther)
			{
				lines.add(otherLine);
			}
		}

		// WEIGHT with icon
		if (info.weight != 0)
		{
			Color weightColor = info.weight < 0 ? positiveColor : valueColor;
			lines.add(new TooltipLine()
				.addIcon("weight")
				.add(String.format("%.1f kg", info.weight), weightColor));
		}
	}

	private String formatBonus(int value)
	{
		return value > 0 ? "+" + value : String.valueOf(value);
	}

	private Color getBonusColor(int value, Color positive, Color negative, Color neutral)
	{
		if (value > 0) return positive;
		if (value < 0) return negative;
		return neutral;
	}

	private Color parseColor(String hex)
	{
		try
		{
			return new Color(Integer.parseInt(hex, 16));
		}
		catch (Exception e)
		{
			return Color.WHITE;
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
		boolean isMembers;
		double weight;
		int geLimit;
		ItemEquipmentStats equipmentStats;
		String examine;
	}

	private static class TooltipLine
	{
		List<TooltipSegment> segments = new ArrayList<>();

		TooltipLine add(String text, Color color)
		{
			segments.add(new TooltipSegment(text, color, null));
			return this;
		}

		TooltipLine addIcon(String iconName)
		{
			segments.add(new TooltipSegment(null, null, iconName));
			return this;
		}
	}

	private static class TooltipSegment
	{
		String text;
		Color color;
		String iconName;

		TooltipSegment(String text, Color color, String iconName)
		{
			this.text = text;
			this.color = color;
			this.iconName = iconName;
		}
	}

	private static class CachedItemTooltip
	{
		BufferedImage icon;
		List<TooltipLine> lines;
	}
}

