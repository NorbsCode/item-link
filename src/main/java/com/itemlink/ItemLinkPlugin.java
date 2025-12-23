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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import net.runelite.client.events.ConfigChanged;
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
	tags = {"item", "link", "chat", "share", "wow", "highlight", "tooltip", "price"},
	enabledByDefault = true
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
	private static final String COLOR_GOLD = "ffd700";        // Gold (for GP amounts under 1M)

	// Default chat colors for restoration after item highlight
	// These are used in transparent chatbox mode
	private static final String COLOR_PUBLIC_CHAT = "9090ff";     // Light blue (public chat)
	private static final String COLOR_FC_CHAT = "ef5050";         // Salmon red (friends/clan chat)

	// Minimum item name length to avoid false positives
	private static final int MIN_ITEM_NAME_LENGTH = 5;

	// Pattern to match money amounts like 1m, 24m, 1.5b, 500k, etc.
	// Matches: number (with optional decimal) followed by k/m/b (case insensitive)
	private static final Pattern MONEY_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)(k|m|b)", Pattern.CASE_INSENSITIVE);

	// Coins item ID
	private static final int COINS_ITEM_ID = 995;

	// Map of common abbreviations/nicknames -> actual item names (lowercase)
	private static final Map<String, String> ITEM_ALIASES = new HashMap<>();
	static
	{
		// Weapons
		ITEM_ALIASES.put("tbow", "twisted bow");
		ITEM_ALIASES.put("bp", "toxic blowpipe");
		ITEM_ALIASES.put("blowpipe", "toxic blowpipe");
		ITEM_ALIASES.put("acb", "armadyl crossbow");
		ITEM_ALIASES.put("dcb", "dragon crossbow");
		ITEM_ALIASES.put("zcb", "zaryte crossbow");
		ITEM_ALIASES.put("rcb", "rune crossbow");
		ITEM_ALIASES.put("msb", "magic shortbow");
		ITEM_ALIASES.put("ags", "armadyl godsword");
		ITEM_ALIASES.put("bgs", "bandos godsword");
		ITEM_ALIASES.put("sgs", "saradomin godsword");
		ITEM_ALIASES.put("zgs", "zamorak godsword");
		ITEM_ALIASES.put("dds", "dragon dagger");
		ITEM_ALIASES.put("dwh", "dragon warhammer");
		ITEM_ALIASES.put("dclaws", "dragon claws");
		ITEM_ALIASES.put("claws", "dragon claws");
		ITEM_ALIASES.put("sang", "sanguinesti staff");
		ITEM_ALIASES.put("scythe", "scythe of vitur");
		ITEM_ALIASES.put("rapier", "ghrazi rapier");
		ITEM_ALIASES.put("bowfa", "bow of faerdhinen");
		ITEM_ALIASES.put("bofa", "bow of faerdhinen");
		ITEM_ALIASES.put("fang", "osmumten's fang");
		ITEM_ALIASES.put("shadow", "tumeken's shadow");
		ITEM_ALIASES.put("kodai", "kodai wand");
		ITEM_ALIASES.put("harm", "harmonised nightmare staff");
		ITEM_ALIASES.put("volatile", "volatile nightmare staff");
		ITEM_ALIASES.put("eldritch", "eldritch nightmare staff");
		ITEM_ALIASES.put("gmaul", "granite maul");
		ITEM_ALIASES.put("tent", "abyssal tentacle");
		ITEM_ALIASES.put("whip", "abyssal whip");
		ITEM_ALIASES.put("dbow", "dark bow");
		ITEM_ALIASES.put("craw", "craw's bow");
		ITEM_ALIASES.put("craws", "craw's bow");
		ITEM_ALIASES.put("vigg", "viggora's chainmace");
		ITEM_ALIASES.put("thamm", "thammaron's sceptre");
		ITEM_ALIASES.put("elder maul", "elder maul");
		ITEM_ALIASES.put("voidwaker", "voidwaker");
		ITEM_ALIASES.put("sotd", "staff of the dead");
		ITEM_ALIASES.put("tsotd", "toxic staff of the dead");
		ITEM_ALIASES.put("toxic sotd", "toxic staff of the dead");
		ITEM_ALIASES.put("trident", "trident of the seas");
		ITEM_ALIASES.put("swamp trident", "trident of the swamp");

		// Dragon items
		ITEM_ALIASES.put("dscim", "dragon scimitar");
		ITEM_ALIASES.put("dlong", "dragon longsword");
		ITEM_ALIASES.put("dbaxe", "dragon battleaxe");
		ITEM_ALIASES.put("d2h", "dragon 2h sword");
		ITEM_ALIASES.put("dhally", "dragon halberd");
		ITEM_ALIASES.put("dspear", "dragon spear");
		ITEM_ALIASES.put("dchain", "dragon chainbody");
		ITEM_ALIASES.put("dlegs", "dragon platelegs");
		ITEM_ALIASES.put("dskirt", "dragon plateskirt");
		ITEM_ALIASES.put("dfh", "dragon full helm");
		ITEM_ALIASES.put("dmed", "dragon med helm");
		ITEM_ALIASES.put("dboots", "dragon boots");
		ITEM_ALIASES.put("dpick", "dragon pickaxe");
		ITEM_ALIASES.put("daxe", "dragon axe");

		// Shields
		ITEM_ALIASES.put("ely", "elysian spirit shield");
		ITEM_ALIASES.put("elysian", "elysian spirit shield");
		ITEM_ALIASES.put("arcane", "arcane spirit shield");
		ITEM_ALIASES.put("spectral", "spectral spirit shield");
		ITEM_ALIASES.put("dfs", "dragonfire shield");
		ITEM_ALIASES.put("dfw", "dragonfire ward");
		ITEM_ALIASES.put("buckler", "twisted buckler");

		// Armor
		ITEM_ALIASES.put("bcp", "bandos chestplate");
		ITEM_ALIASES.put("tassets", "bandos tassets");
		ITEM_ALIASES.put("tassies", "bandos tassets");
		ITEM_ALIASES.put("prims", "primordial boots");
		ITEM_ALIASES.put("pegs", "pegasian boots");
		ITEM_ALIASES.put("eternals", "eternal boots");
		ITEM_ALIASES.put("serp", "serpentine helm");
		ITEM_ALIASES.put("serp helm", "serpentine helm");
		ITEM_ALIASES.put("tanz helm", "tanzanite helm");
		ITEM_ALIASES.put("magma helm", "magma helm");
		ITEM_ALIASES.put("nezzy", "neitiznot faceguard");
		ITEM_ALIASES.put("faceguard", "neitiznot faceguard");
		ITEM_ALIASES.put("arma helm", "armadyl helmet");
		ITEM_ALIASES.put("arma chest", "armadyl chestplate");
		ITEM_ALIASES.put("arma legs", "armadyl chainskirt");
		ITEM_ALIASES.put("arma skirt", "armadyl chainskirt");
		ITEM_ALIASES.put("ancestral hat", "ancestral hat");
		ITEM_ALIASES.put("ancestral top", "ancestral robe top");
		ITEM_ALIASES.put("ancestral bottom", "ancestral robe bottom");
		ITEM_ALIASES.put("ancestral bottoms", "ancestral robe bottom");

		// Jewelry
		ITEM_ALIASES.put("fury", "amulet of fury");
		ITEM_ALIASES.put("torture", "amulet of torture");
		ITEM_ALIASES.put("anguish", "necklace of anguish");
		ITEM_ALIASES.put("tormented", "tormented bracelet");
		ITEM_ALIASES.put("occult", "occult necklace");
		ITEM_ALIASES.put("suffering", "ring of suffering");
		ITEM_ALIASES.put("b ring", "berserker ring");
		ITEM_ALIASES.put("b ring i", "berserker ring (i)");
		ITEM_ALIASES.put("zerker ring", "berserker ring");
		ITEM_ALIASES.put("archers ring", "archers ring");
		ITEM_ALIASES.put("seers ring", "seers ring");
		ITEM_ALIASES.put("warrior ring", "warrior ring");
		ITEM_ALIASES.put("lightbearer", "lightbearer");
		ITEM_ALIASES.put("ultor", "ultor ring");
		ITEM_ALIASES.put("venator", "venator ring");
		ITEM_ALIASES.put("magus", "magus ring");
		ITEM_ALIASES.put("bellator", "bellator ring");

		// Third age aliases
		ITEM_ALIASES.put("third age full helm", "3rd age full helmet");
		ITEM_ALIASES.put("third age helm", "3rd age full helmet");
		ITEM_ALIASES.put("third age platebody", "3rd age platebody");
		ITEM_ALIASES.put("third age platelegs", "3rd age platelegs");
		ITEM_ALIASES.put("third age kiteshield", "3rd age kiteshield");
		ITEM_ALIASES.put("third age mage hat", "3rd age mage hat");
		ITEM_ALIASES.put("third age robe top", "3rd age robe top");
		ITEM_ALIASES.put("third age robe", "3rd age robe");
		ITEM_ALIASES.put("third age range top", "3rd age range top");
		ITEM_ALIASES.put("third age range legs", "3rd age range legs");
		ITEM_ALIASES.put("third age range coif", "3rd age range coif");
		ITEM_ALIASES.put("third age vambraces", "3rd age vambraces");
		ITEM_ALIASES.put("third age cloak", "3rd age cloak");
		ITEM_ALIASES.put("third age longsword", "3rd age longsword");
		ITEM_ALIASES.put("third age bow", "3rd age bow");
		ITEM_ALIASES.put("third age wand", "3rd age wand");
		ITEM_ALIASES.put("third age amulet", "3rd age amulet");
		ITEM_ALIASES.put("third age druidic", "3rd age druidic robe top");
		ITEM_ALIASES.put("third age pickaxe", "3rd age pickaxe");
		ITEM_ALIASES.put("third age axe", "3rd age axe");
		ITEM_ALIASES.put("3a full helm", "3rd age full helmet");
		ITEM_ALIASES.put("3a platebody", "3rd age platebody");
		ITEM_ALIASES.put("3a platelegs", "3rd age platelegs");
		ITEM_ALIASES.put("3a kiteshield", "3rd age kiteshield");
		ITEM_ALIASES.put("3a mage hat", "3rd age mage hat");
		ITEM_ALIASES.put("3a robe top", "3rd age robe top");
		ITEM_ALIASES.put("3a robe", "3rd age robe");
		ITEM_ALIASES.put("3a range top", "3rd age range top");
		ITEM_ALIASES.put("3a range legs", "3rd age range legs");
		ITEM_ALIASES.put("3a range coif", "3rd age range coif");
		ITEM_ALIASES.put("3a vambraces", "3rd age vambraces");
		ITEM_ALIASES.put("3a cloak", "3rd age cloak");
		ITEM_ALIASES.put("3a longsword", "3rd age longsword");
		ITEM_ALIASES.put("3a bow", "3rd age bow");
		ITEM_ALIASES.put("3a wand", "3rd age wand");
		ITEM_ALIASES.put("3a amulet", "3rd age amulet");
		ITEM_ALIASES.put("3a pickaxe", "3rd age pickaxe");
		ITEM_ALIASES.put("3a axe", "3rd age axe");

		// Barrows
		ITEM_ALIASES.put("dharoks", "dharok's greataxe");
		ITEM_ALIASES.put("veracs", "verac's flail");
		ITEM_ALIASES.put("guthans", "guthan's warspear");
		ITEM_ALIASES.put("torags", "torag's hammers");
		ITEM_ALIASES.put("karils", "karil's crossbow");
		ITEM_ALIASES.put("ahrims", "ahrim's staff");

		// Other popular items
		ITEM_ALIASES.put("obby maul", "tzhaar-ket-om");
		ITEM_ALIASES.put("obby cape", "obsidian cape");
		ITEM_ALIASES.put("fire cape", "fire cape");
		ITEM_ALIASES.put("infernal", "infernal cape");
		ITEM_ALIASES.put("infernal cape", "infernal cape");
		ITEM_ALIASES.put("assembler", "ava's assembler");
		ITEM_ALIASES.put("max cape", "max cape");
		ITEM_ALIASES.put("dcape", "infernal cape");
		ITEM_ALIASES.put("fcape", "fire cape");
		ITEM_ALIASES.put("slay helm", "slayer helmet");
		ITEM_ALIASES.put("slayer helm", "slayer helmet");
		ITEM_ALIASES.put("black mask", "black mask");
		ITEM_ALIASES.put("phoenix", "phoenix");
		ITEM_ALIASES.put("pet", "pet");

		// Material abbreviations - Ores
		ITEM_ALIASES.put("addy ore", "adamantite ore");
		ITEM_ALIASES.put("addy ores", "adamantite ore");
		ITEM_ALIASES.put("adamantite ores", "adamantite ore");
		ITEM_ALIASES.put("mith ore", "mithril ore");
		ITEM_ALIASES.put("mith ores", "mithril ore");
		ITEM_ALIASES.put("mithril ores", "mithril ore");
		ITEM_ALIASES.put("rune ore", "runite ore");
		ITEM_ALIASES.put("rune ores", "runite ore");
		ITEM_ALIASES.put("runite ores", "runite ore");
		ITEM_ALIASES.put("iron ores", "iron ore");
		ITEM_ALIASES.put("coal ores", "coal");
		ITEM_ALIASES.put("gold ores", "gold ore");
		ITEM_ALIASES.put("silver ores", "silver ore");
		ITEM_ALIASES.put("copper ores", "copper ore");
		ITEM_ALIASES.put("tin ores", "tin ore");

		// Material abbreviations - Bars
		ITEM_ALIASES.put("addy bar", "adamantite bar");
		ITEM_ALIASES.put("addy bars", "adamantite bar");
		ITEM_ALIASES.put("adamantite bars", "adamantite bar");
		ITEM_ALIASES.put("mith bar", "mithril bar");
		ITEM_ALIASES.put("mith bars", "mithril bar");
		ITEM_ALIASES.put("mithril bars", "mithril bar");
		ITEM_ALIASES.put("rune bar", "runite bar");
		ITEM_ALIASES.put("rune bars", "runite bar");
		ITEM_ALIASES.put("runite bars", "runite bar");
		ITEM_ALIASES.put("iron bars", "iron bar");
		ITEM_ALIASES.put("steel bars", "steel bar");
		ITEM_ALIASES.put("gold bars", "gold bar");
		ITEM_ALIASES.put("silver bars", "silver bar");
		ITEM_ALIASES.put("bronze bars", "bronze bar");

		// Dart tips
		ITEM_ALIASES.put("addy dart tips", "adamant dart tip");
		ITEM_ALIASES.put("addy dart tip", "adamant dart tip");
		ITEM_ALIASES.put("adamant dart tips", "adamant dart tip");
		ITEM_ALIASES.put("mith dart tips", "mithril dart tip");
		ITEM_ALIASES.put("mith dart tip", "mithril dart tip");
		ITEM_ALIASES.put("mithril dart tips", "mithril dart tip");
		ITEM_ALIASES.put("rune dart tips", "rune dart tip");
		ITEM_ALIASES.put("rune dart tip", "rune dart tip");
		ITEM_ALIASES.put("dragon dart tips", "dragon dart tip");
		ITEM_ALIASES.put("dragon dart tip", "dragon dart tip");
		ITEM_ALIASES.put("iron dart tips", "iron dart tip");
		ITEM_ALIASES.put("steel dart tips", "steel dart tip");
		ITEM_ALIASES.put("bronze dart tips", "bronze dart tip");

		// Darts
		ITEM_ALIASES.put("addy darts", "adamant dart");
		ITEM_ALIASES.put("addy dart", "adamant dart");
		ITEM_ALIASES.put("mith darts", "mithril dart");
		ITEM_ALIASES.put("mith dart", "mithril dart");
		ITEM_ALIASES.put("rune darts", "rune dart");
		ITEM_ALIASES.put("dragon darts", "dragon dart");
		ITEM_ALIASES.put("ddarts", "dragon dart");

		// Arrows
		ITEM_ALIASES.put("addy arrows", "adamant arrow");
		ITEM_ALIASES.put("addy arrow", "adamant arrow");
		ITEM_ALIASES.put("mith arrows", "mithril arrow");
		ITEM_ALIASES.put("mith arrow", "mithril arrow");
		ITEM_ALIASES.put("rune arrows", "rune arrow");
		ITEM_ALIASES.put("dragon arrows", "dragon arrow");
		ITEM_ALIASES.put("amethyst arrows", "amethyst arrow");
		ITEM_ALIASES.put("iron arrows", "iron arrow");
		ITEM_ALIASES.put("steel arrows", "steel arrow");
		ITEM_ALIASES.put("bronze arrows", "bronze arrow");

		// Bolts
		ITEM_ALIASES.put("addy bolts", "adamant bolts");
		ITEM_ALIASES.put("mith bolts", "mithril bolts");
		ITEM_ALIASES.put("rune bolts", "runite bolts");
		ITEM_ALIASES.put("dragon bolts", "dragon bolts");
		ITEM_ALIASES.put("ruby bolts", "ruby bolts (e)");
		ITEM_ALIASES.put("diamond bolts", "diamond bolts (e)");
		ITEM_ALIASES.put("dragonstone bolts", "dragonstone bolts (e)");
		ITEM_ALIASES.put("onyx bolts", "onyx bolts (e)");

		// Arrowtips
		ITEM_ALIASES.put("addy arrowtips", "adamant arrowtips");
		ITEM_ALIASES.put("mith arrowtips", "mithril arrowtips");
		ITEM_ALIASES.put("rune arrowtips", "rune arrowtips");
		ITEM_ALIASES.put("dragon arrowtips", "dragon arrowtips");
		ITEM_ALIASES.put("amethyst arrowtips", "amethyst arrowtips");

		// Common plurals
		ITEM_ALIASES.put("feathers", "feather");
		ITEM_ALIASES.put("bones", "bones");
		ITEM_ALIASES.put("big bones", "big bones");
		ITEM_ALIASES.put("dragon bones", "dragon bones");
		ITEM_ALIASES.put("superior dragon bones", "superior dragon bones");
		ITEM_ALIASES.put("wyvern bones", "wyvern bones");
		ITEM_ALIASES.put("lava dragon bones", "lava dragon bones");
		ITEM_ALIASES.put("dagannoth bones", "dagannoth bones");
		ITEM_ALIASES.put("ensouled heads", "ensouled dragon head");
		ITEM_ALIASES.put("coins", "coins");
		ITEM_ALIASES.put("gp", "coins");
		ITEM_ALIASES.put("sharks", "shark");
		ITEM_ALIASES.put("anglers", "anglerfish");
		ITEM_ALIASES.put("anglerfish", "anglerfish");
		ITEM_ALIASES.put("mantas", "manta ray");
		ITEM_ALIASES.put("manta rays", "manta ray");
		ITEM_ALIASES.put("karams", "karambwan");
		ITEM_ALIASES.put("karambwans", "karambwan");
		ITEM_ALIASES.put("lobbies", "lobster");
		ITEM_ALIASES.put("lobsters", "lobster");
		ITEM_ALIASES.put("monks", "monkfish");
		ITEM_ALIASES.put("monkfish", "monkfish");
		// Potions - always show (4) dose
		ITEM_ALIASES.put("brews", "saradomin brew(4)");
		ITEM_ALIASES.put("brew", "saradomin brew(4)");
		ITEM_ALIASES.put("sara brew", "saradomin brew(4)");
		ITEM_ALIASES.put("sara brews", "saradomin brew(4)");
		ITEM_ALIASES.put("saradomin brew", "saradomin brew(4)");
		ITEM_ALIASES.put("saradomin brews", "saradomin brew(4)");
		ITEM_ALIASES.put("restores", "super restore(4)");
		ITEM_ALIASES.put("restore", "super restore(4)");
		ITEM_ALIASES.put("super restore", "super restore(4)");
		ITEM_ALIASES.put("super restores", "super restore(4)");
		ITEM_ALIASES.put("ppot", "prayer potion(4)");
		ITEM_ALIASES.put("ppots", "prayer potion(4)");
		ITEM_ALIASES.put("prayer pot", "prayer potion(4)");
		ITEM_ALIASES.put("prayer pots", "prayer potion(4)");
		ITEM_ALIASES.put("prayer potion", "prayer potion(4)");
		ITEM_ALIASES.put("prayer potions", "prayer potion(4)");
		ITEM_ALIASES.put("range pot", "ranging potion(4)");
		ITEM_ALIASES.put("range pots", "ranging potion(4)");
		ITEM_ALIASES.put("ranging pot", "ranging potion(4)");
		ITEM_ALIASES.put("ranging pots", "ranging potion(4)");
		ITEM_ALIASES.put("ranging potion", "ranging potion(4)");
		ITEM_ALIASES.put("ranging potions", "ranging potion(4)");
		ITEM_ALIASES.put("super combat", "super combat potion(4)");
		ITEM_ALIASES.put("super combats", "super combat potion(4)");
		ITEM_ALIASES.put("scb", "super combat potion(4)");
		ITEM_ALIASES.put("divine super combat", "divine super combat potion(4)");
		ITEM_ALIASES.put("divine super combats", "divine super combat potion(4)");
		ITEM_ALIASES.put("divine scb", "divine super combat potion(4)");
		ITEM_ALIASES.put("stam", "stamina potion(4)");
		ITEM_ALIASES.put("stams", "stamina potion(4)");
		ITEM_ALIASES.put("stamina", "stamina potion(4)");
		ITEM_ALIASES.put("staminas", "stamina potion(4)");
		ITEM_ALIASES.put("stamina pot", "stamina potion(4)");
		ITEM_ALIASES.put("stamina pots", "stamina potion(4)");
		ITEM_ALIASES.put("stamina potion", "stamina potion(4)");
		ITEM_ALIASES.put("stamina potions", "stamina potion(4)");
		ITEM_ALIASES.put("antifire", "antifire potion(4)");
		ITEM_ALIASES.put("antifires", "antifire potion(4)");
		ITEM_ALIASES.put("antifire pot", "antifire potion(4)");
		ITEM_ALIASES.put("antifire potion", "antifire potion(4)");
		ITEM_ALIASES.put("super antifire", "super antifire potion(4)");
		ITEM_ALIASES.put("super antifires", "super antifire potion(4)");
		ITEM_ALIASES.put("super antifire potion", "super antifire potion(4)");
		ITEM_ALIASES.put("extended antifire", "extended antifire(4)");
		ITEM_ALIASES.put("extended antifires", "extended antifire(4)");
		ITEM_ALIASES.put("extended super antifire", "extended super antifire(4)");
		ITEM_ALIASES.put("extended super antifires", "extended super antifire(4)");
		ITEM_ALIASES.put("antivenom", "anti-venom(4)");
		ITEM_ALIASES.put("antivenoms", "anti-venom(4)");
		ITEM_ALIASES.put("anti venom", "anti-venom(4)");
		ITEM_ALIASES.put("antivenom+", "anti-venom+(4)");
		ITEM_ALIASES.put("anti venom+", "anti-venom+(4)");
		ITEM_ALIASES.put("sanfew", "sanfew serum(4)");
		ITEM_ALIASES.put("sanfews", "sanfew serum(4)");
		ITEM_ALIASES.put("sanfew serum", "sanfew serum(4)");
		ITEM_ALIASES.put("sanfew serums", "sanfew serum(4)");
		ITEM_ALIASES.put("super att", "super attack(4)");
		ITEM_ALIASES.put("super attack", "super attack(4)");
		ITEM_ALIASES.put("super str", "super strength(4)");
		ITEM_ALIASES.put("super strength", "super strength(4)");
		ITEM_ALIASES.put("super def", "super defence(4)");
		ITEM_ALIASES.put("super defence", "super defence(4)");
		ITEM_ALIASES.put("super defense", "super defence(4)");
		ITEM_ALIASES.put("antipoison", "antipoison(4)");
		ITEM_ALIASES.put("super antipoison", "superantipoison(4)");
		ITEM_ALIASES.put("energy pot", "energy potion(4)");
		ITEM_ALIASES.put("energy potion", "energy potion(4)");
		ITEM_ALIASES.put("super energy", "super energy(4)");
		ITEM_ALIASES.put("magic pot", "magic potion(4)");
		ITEM_ALIASES.put("magic potion", "magic potion(4)");
		ITEM_ALIASES.put("bastion", "bastion potion(4)");
		ITEM_ALIASES.put("bastion pot", "bastion potion(4)");
		ITEM_ALIASES.put("bastion potion", "bastion potion(4)");
		ITEM_ALIASES.put("battlemage", "battlemage potion(4)");
		ITEM_ALIASES.put("battlemage pot", "battlemage potion(4)");
		ITEM_ALIASES.put("battlemage potion", "battlemage potion(4)");
		ITEM_ALIASES.put("divine ranging", "divine ranging potion(4)");
		ITEM_ALIASES.put("divine range", "divine ranging potion(4)");
		ITEM_ALIASES.put("divine bastion", "divine bastion potion(4)");
		ITEM_ALIASES.put("divine battlemage", "divine battlemage potion(4)");
		ITEM_ALIASES.put("imbued heart", "imbued heart");
		ITEM_ALIASES.put("saturated heart", "saturated heart");

		// Herbs
		ITEM_ALIASES.put("ranarrs", "ranarr weed");
		ITEM_ALIASES.put("ranarr", "ranarr weed");
		ITEM_ALIASES.put("snaps", "snapdragon");
		ITEM_ALIASES.put("snapdragons", "snapdragon");
		ITEM_ALIASES.put("torstols", "torstol");
		ITEM_ALIASES.put("toadflax", "toadflax");
		ITEM_ALIASES.put("kwuarms", "kwuarm");
		ITEM_ALIASES.put("cadantines", "cadantine");
		ITEM_ALIASES.put("lantadymes", "lantadyme");
		ITEM_ALIASES.put("dwarf weeds", "dwarf weed");

		// Seeds
		ITEM_ALIASES.put("ranarr seeds", "ranarr seed");
		ITEM_ALIASES.put("snapdragon seeds", "snapdragon seed");
		ITEM_ALIASES.put("torstol seeds", "torstol seed");
		ITEM_ALIASES.put("palm seeds", "palm tree seed");
		ITEM_ALIASES.put("palm tree seeds", "palm tree seed");
		ITEM_ALIASES.put("magic seeds", "magic seed");
		ITEM_ALIASES.put("dragonfruit seeds", "dragonfruit tree seed");
		ITEM_ALIASES.put("celastrus seeds", "celastrus seed");
		ITEM_ALIASES.put("redwood seeds", "redwood tree seed");
		ITEM_ALIASES.put("hespori seeds", "hespori seed");

		// Logs
		ITEM_ALIASES.put("yews", "yew logs");
		ITEM_ALIASES.put("yew logs", "yew logs");
		ITEM_ALIASES.put("magics", "magic logs");
		ITEM_ALIASES.put("magic logs", "magic logs");
		ITEM_ALIASES.put("redwoods", "redwood logs");
		ITEM_ALIASES.put("redwood logs", "redwood logs");
		ITEM_ALIASES.put("maples", "maple logs");
		ITEM_ALIASES.put("maple logs", "maple logs");

		// Runes
		ITEM_ALIASES.put("nats", "nature rune");
		ITEM_ALIASES.put("nature runes", "nature rune");
		ITEM_ALIASES.put("laws", "law rune");
		ITEM_ALIASES.put("law runes", "law rune");
		ITEM_ALIASES.put("deaths", "death rune");
		ITEM_ALIASES.put("death runes", "death rune");
		ITEM_ALIASES.put("bloods", "blood rune");
		ITEM_ALIASES.put("blood runes", "blood rune");
		ITEM_ALIASES.put("souls", "soul rune");
		ITEM_ALIASES.put("soul runes", "soul rune");
		ITEM_ALIASES.put("wraths", "wrath rune");
		ITEM_ALIASES.put("wrath runes", "wrath rune");
		ITEM_ALIASES.put("astrals", "astral rune");
		ITEM_ALIASES.put("astral runes", "astral rune");
		ITEM_ALIASES.put("cosmics", "cosmic rune");
		ITEM_ALIASES.put("cosmic runes", "cosmic rune");
		ITEM_ALIASES.put("chaos runes", "chaos rune");
		ITEM_ALIASES.put("fire runes", "fire rune");
		ITEM_ALIASES.put("water runes", "water rune");
		ITEM_ALIASES.put("air runes", "air rune");
		ITEM_ALIASES.put("earth runes", "earth rune");
		ITEM_ALIASES.put("mind runes", "mind rune");
		ITEM_ALIASES.put("body runes", "body rune");

		// Gems
		ITEM_ALIASES.put("zenytes", "zenyte");
		ITEM_ALIASES.put("onyxes", "onyx");
		ITEM_ALIASES.put("dragonstones", "dragonstone");
		ITEM_ALIASES.put("diamonds", "diamond");
		ITEM_ALIASES.put("rubies", "ruby");
		ITEM_ALIASES.put("emeralds", "emerald");
		ITEM_ALIASES.put("sapphires", "sapphire");

		// Leather/hides
		ITEM_ALIASES.put("black dhide", "black dragonhide");
		ITEM_ALIASES.put("black dhides", "black dragonhide");
		ITEM_ALIASES.put("black d'hide", "black dragonhide");
		ITEM_ALIASES.put("red dhide", "red dragonhide");
		ITEM_ALIASES.put("blue dhide", "blue dragonhide");
		ITEM_ALIASES.put("green dhide", "green dragonhide");
		ITEM_ALIASES.put("black dhide body", "black d'hide body");
		ITEM_ALIASES.put("black dhide chaps", "black d'hide chaps");
		ITEM_ALIASES.put("black dhide vambs", "black d'hide vambraces");
		ITEM_ALIASES.put("black d'hide vambs", "black d'hide vambraces");

		// Essence
		ITEM_ALIASES.put("pure ess", "pure essence");
		ITEM_ALIASES.put("pure essence", "pure essence");
		ITEM_ALIASES.put("rune ess", "rune essence");
		ITEM_ALIASES.put("rune essence", "rune essence");
		ITEM_ALIASES.put("daeyalt ess", "daeyalt essence");
		ITEM_ALIASES.put("daeyalt essence", "daeyalt essence");

		// Orbs
		ITEM_ALIASES.put("air orbs", "air orb");
		ITEM_ALIASES.put("water orbs", "water orb");
		ITEM_ALIASES.put("earth orbs", "earth orb");
		ITEM_ALIASES.put("fire orbs", "fire orb");
		ITEM_ALIASES.put("unpowered orbs", "unpowered orb");

		// Battlestaves
		ITEM_ALIASES.put("bstaves", "battlestaff");
		ITEM_ALIASES.put("bstaff", "battlestaff");
		ITEM_ALIASES.put("battlestaves", "battlestaff");
		ITEM_ALIASES.put("air bstaff", "air battlestaff");
		ITEM_ALIASES.put("water bstaff", "water battlestaff");
		ITEM_ALIASES.put("earth bstaff", "earth battlestaff");
		ITEM_ALIASES.put("fire bstaff", "fire battlestaff");
		ITEM_ALIASES.put("mystic air staff", "mystic air staff");
		ITEM_ALIASES.put("mystic water staff", "mystic water staff");
		ITEM_ALIASES.put("mystic earth staff", "mystic earth staff");
		ITEM_ALIASES.put("mystic fire staff", "mystic fire staff");

		// Cannonballs
		ITEM_ALIASES.put("cballs", "cannonball");
		ITEM_ALIASES.put("cball", "cannonball");
		ITEM_ALIASES.put("cannonballs", "cannonball");

		// Javelin
		ITEM_ALIASES.put("addy javs", "adamant javelin");
		ITEM_ALIASES.put("rune javs", "rune javelin");
		ITEM_ALIASES.put("dragon javs", "dragon javelin");
		ITEM_ALIASES.put("amethyst javs", "amethyst javelin");
		ITEM_ALIASES.put("javelins", "rune javelin");

		// Knives
		ITEM_ALIASES.put("rune knives", "rune knife");
		ITEM_ALIASES.put("dragon knives", "dragon knife");
		ITEM_ALIASES.put("dknives", "dragon knife");
		ITEM_ALIASES.put("dknife", "dragon knife");

		// Thrown axes
		ITEM_ALIASES.put("rune thrownaxes", "rune thrownaxe");
		ITEM_ALIASES.put("dragon thrownaxes", "dragon thrownaxe");

		// Scales and resources
		ITEM_ALIASES.put("zulrah scales", "zulrah's scales");
		ITEM_ALIASES.put("scales", "zulrah's scales");
		ITEM_ALIASES.put("snakeskin", "snakeskin");
		ITEM_ALIASES.put("snakeskins", "snakeskin");
		ITEM_ALIASES.put("mort myre fungus", "mort myre fungus");
		ITEM_ALIASES.put("mort myre fungi", "mort myre fungus");
		ITEM_ALIASES.put("white berries", "white berries");
		ITEM_ALIASES.put("whiteberries", "white berries");
		ITEM_ALIASES.put("limpwurt roots", "limpwurt root");
		ITEM_ALIASES.put("limpwurts", "limpwurt root");
		ITEM_ALIASES.put("red spiders eggs", "red spiders' eggs");
		ITEM_ALIASES.put("red spider eggs", "red spiders' eggs");
		ITEM_ALIASES.put("spider eggs", "red spiders' eggs");
		ITEM_ALIASES.put("crushed nests", "crushed nest");
		ITEM_ALIASES.put("bird nests", "bird nest");
		ITEM_ALIASES.put("nests", "bird nest");
		ITEM_ALIASES.put("dragon scale dust", "dragon scale dust");
		ITEM_ALIASES.put("wine of zamorak", "wine of zamorak");
		ITEM_ALIASES.put("zammy wines", "wine of zamorak");
		ITEM_ALIASES.put("zammy wine", "wine of zamorak");
		ITEM_ALIASES.put("potato cactus", "potato cactus");
		ITEM_ALIASES.put("potato cacti", "potato cactus");
		ITEM_ALIASES.put("goat horns", "goat horn dust");
		ITEM_ALIASES.put("goat horn", "goat horn dust");
		ITEM_ALIASES.put("unicorn horns", "unicorn horn dust");
		ITEM_ALIASES.put("unicorn horn", "unicorn horn dust");

		// Secondaries
		ITEM_ALIASES.put("eyes of newt", "eye of newt");
		ITEM_ALIASES.put("eye of newts", "eye of newt");
		ITEM_ALIASES.put("swamp tar", "swamp tar");
		ITEM_ALIASES.put("swamp tars", "swamp tar");
		ITEM_ALIASES.put("volcanic ash", "volcanic ash");
		ITEM_ALIASES.put("volcanic ashes", "volcanic ash");
		ITEM_ALIASES.put("crystal shards", "crystal shard");
		ITEM_ALIASES.put("shards", "crystal shard");

		// Planks
		ITEM_ALIASES.put("planks", "plank");
		ITEM_ALIASES.put("oak planks", "oak plank");
		ITEM_ALIASES.put("teak planks", "teak plank");
		ITEM_ALIASES.put("mahogany planks", "mahogany plank");
		ITEM_ALIASES.put("mahog planks", "mahogany plank");
		ITEM_ALIASES.put("mahog plank", "mahogany plank");

		// Nails
		ITEM_ALIASES.put("iron nails", "iron nails");
		ITEM_ALIASES.put("steel nails", "steel nails");
		ITEM_ALIASES.put("mith nails", "mithril nails");
		ITEM_ALIASES.put("addy nails", "adamantite nails");
		ITEM_ALIASES.put("rune nails", "rune nails");

		// Ensouled heads
		ITEM_ALIASES.put("ensouled giant heads", "ensouled giant head");
		ITEM_ALIASES.put("ensouled dragon heads", "ensouled dragon head");
		ITEM_ALIASES.put("ensouled demon heads", "ensouled demon head");
		ITEM_ALIASES.put("ensouled aviansie heads", "ensouled aviansie head");
		ITEM_ALIASES.put("ensouled abyssal heads", "ensouled abyssal head");

		// Dragon items plural
		ITEM_ALIASES.put("dragon bones", "dragon bones");
		ITEM_ALIASES.put("d bones", "dragon bones");
		ITEM_ALIASES.put("dbones", "dragon bones");

		// Slayer items
		ITEM_ALIASES.put("superiors", "superior dragon bones");
		ITEM_ALIASES.put("superior bones", "superior dragon bones");
		ITEM_ALIASES.put("imbued heart", "imbued heart");
		ITEM_ALIASES.put("eternal gem", "eternal gem");
		ITEM_ALIASES.put("dust bstaff", "dust battlestaff");
		ITEM_ALIASES.put("mist bstaff", "mist battlestaff");
		ITEM_ALIASES.put("smoke bstaff", "smoke battlestaff");
		ITEM_ALIASES.put("steam bstaff", "steam battlestaff");

		// Keys
		ITEM_ALIASES.put("larrans keys", "larran's key");
		ITEM_ALIASES.put("larran key", "larran's key");
		ITEM_ALIASES.put("larrans key", "larran's key");
		ITEM_ALIASES.put("brimstone keys", "brimstone key");
		ITEM_ALIASES.put("crystal keys", "crystal key");

		// Clue items
		ITEM_ALIASES.put("easy clues", "clue scroll (easy)");
		ITEM_ALIASES.put("easy clue", "clue scroll (easy)");
		ITEM_ALIASES.put("medium clues", "clue scroll (medium)");
		ITEM_ALIASES.put("medium clue", "clue scroll (medium)");
		ITEM_ALIASES.put("hard clues", "clue scroll (hard)");
		ITEM_ALIASES.put("hard clue", "clue scroll (hard)");
		ITEM_ALIASES.put("elite clues", "clue scroll (elite)");
		ITEM_ALIASES.put("elite clue", "clue scroll (elite)");
		ITEM_ALIASES.put("master clues", "clue scroll (master)");
		ITEM_ALIASES.put("master clue", "clue scroll (master)");

		// Boss uniques
		ITEM_ALIASES.put("mutagens", "tanzanite mutagen");
		ITEM_ALIASES.put("tanz mutagen", "tanzanite mutagen");
		ITEM_ALIASES.put("magma mutagen", "magma mutagen");
		ITEM_ALIASES.put("onyx", "onyx");
		ITEM_ALIASES.put("tanz fang", "tanzanite fang");
		ITEM_ALIASES.put("magic fang", "magic fang");
		ITEM_ALIASES.put("serp visage", "serpentine visage");
		ITEM_ALIASES.put("visage", "draconic visage");
		ITEM_ALIASES.put("skeletal visage", "skeletal visage");
		ITEM_ALIASES.put("wyvern visage", "wyvern visage");
		ITEM_ALIASES.put("jar of souls", "jar of souls");
		ITEM_ALIASES.put("jar of swamp", "jar of swamp");
		ITEM_ALIASES.put("jar of dirt", "jar of dirt");
		ITEM_ALIASES.put("jar of sand", "jar of sand");
		ITEM_ALIASES.put("jar of stone", "jar of stone");
		ITEM_ALIASES.put("jar of darkness", "jar of darkness");
		ITEM_ALIASES.put("jar of decay", "jar of decay");
		ITEM_ALIASES.put("jar of dreams", "jar of dreams");
		ITEM_ALIASES.put("jar of eyes", "jar of eyes");
		ITEM_ALIASES.put("jar of miasma", "jar of miasma");
		ITEM_ALIASES.put("jars", "jar of swamp");
		ITEM_ALIASES.put("pet snakeling", "pet snakeling");
		ITEM_ALIASES.put("snakeling", "pet snakeling");
		ITEM_ALIASES.put("olmlet", "olmlet");
		ITEM_ALIASES.put("little nightmare", "little nightmare");
		ITEM_ALIASES.put("lil zik", "lil' zik");
		ITEM_ALIASES.put("tumekens guardian", "tumeken's guardian");
		ITEM_ALIASES.put("elidinis guardian", "elidinis' guardian");

		// ToB uniques
		ITEM_ALIASES.put("avernic", "avernic defender hilt");
		ITEM_ALIASES.put("avernic hilt", "avernic defender hilt");
		ITEM_ALIASES.put("avernic defender", "avernic defender");
		ITEM_ALIASES.put("justi helm", "justiciar faceguard");
		ITEM_ALIASES.put("justi chest", "justiciar chestguard");
		ITEM_ALIASES.put("justi legs", "justiciar legguards");
		ITEM_ALIASES.put("justi faceguard", "justiciar faceguard");
		ITEM_ALIASES.put("justi chestguard", "justiciar chestguard");
		ITEM_ALIASES.put("justi legguards", "justiciar legguards");
		ITEM_ALIASES.put("sanguine dust", "sanguine dust");
		ITEM_ALIASES.put("sang dust", "sanguine dust");
		ITEM_ALIASES.put("holy ornament kit", "holy ornament kit");
		ITEM_ALIASES.put("sanguine ornament kit", "sanguine ornament kit");

		// CoX uniques
		ITEM_ALIASES.put("dex", "dexterous prayer scroll");
		ITEM_ALIASES.put("dex scroll", "dexterous prayer scroll");
		ITEM_ALIASES.put("dexterous", "dexterous prayer scroll");
		ITEM_ALIASES.put("arcane scroll", "arcane prayer scroll");
		ITEM_ALIASES.put("arc scroll", "arcane prayer scroll");
		ITEM_ALIASES.put("tbow", "twisted bow");
		ITEM_ALIASES.put("dhcb", "dragon hunter crossbow");
		ITEM_ALIASES.put("dragon hunter", "dragon hunter crossbow");
		ITEM_ALIASES.put("dhl", "dragon hunter lance");
		ITEM_ALIASES.put("dragon hunter lance", "dragon hunter lance");
		ITEM_ALIASES.put("din's bulwark", "dinh's bulwark");
		ITEM_ALIASES.put("dins bulwark", "dinh's bulwark");
		ITEM_ALIASES.put("dinhs bulwark", "dinh's bulwark");
		ITEM_ALIASES.put("dinhs", "dinh's bulwark");
		ITEM_ALIASES.put("bulwark", "dinh's bulwark");
		ITEM_ALIASES.put("elder maul", "elder maul");
		ITEM_ALIASES.put("anc hat", "ancestral hat");
		ITEM_ALIASES.put("anc top", "ancestral robe top");
		ITEM_ALIASES.put("anc bottom", "ancestral robe bottom");
		ITEM_ALIASES.put("anc bottoms", "ancestral robe bottom");

		// ToA uniques
		ITEM_ALIASES.put("fang", "osmumten's fang");
		ITEM_ALIASES.put("osmumtens fang", "osmumten's fang");
		ITEM_ALIASES.put("lightbearer", "lightbearer");
		ITEM_ALIASES.put("light bearer", "lightbearer");
		ITEM_ALIASES.put("shadow", "tumeken's shadow");
		ITEM_ALIASES.put("tumekens shadow", "tumeken's shadow");
		ITEM_ALIASES.put("masori mask", "masori mask");
		ITEM_ALIASES.put("masori body", "masori body");
		ITEM_ALIASES.put("masori chaps", "masori chaps");
		ITEM_ALIASES.put("masori helm", "masori mask");
		ITEM_ALIASES.put("masori top", "masori body");
		ITEM_ALIASES.put("masori legs", "masori chaps");
		ITEM_ALIASES.put("elidinis ward", "elidinis' ward");
		ITEM_ALIASES.put("ward", "elidinis' ward");

		// GWD uniques
		ITEM_ALIASES.put("acb", "armadyl crossbow");
		ITEM_ALIASES.put("arma crossbow", "armadyl crossbow");
		ITEM_ALIASES.put("arma cbow", "armadyl crossbow");
		ITEM_ALIASES.put("steam staff", "steam battlestaff");
		ITEM_ALIASES.put("zammy spear", "zamorakian spear");
		ITEM_ALIASES.put("zspear", "zamorakian spear");
		ITEM_ALIASES.put("zammy hasta", "zamorakian hasta");
		ITEM_ALIASES.put("zhasta", "zamorakian hasta");
		ITEM_ALIASES.put("sotd", "staff of the dead");
		ITEM_ALIASES.put("saradomin sword", "saradomin sword");
		ITEM_ALIASES.put("ss", "saradomin sword");
		ITEM_ALIASES.put("sara sword", "saradomin sword");
		ITEM_ALIASES.put("saras light", "saradomin's light");
		ITEM_ALIASES.put("sara light", "saradomin's light");
		ITEM_ALIASES.put("hilt", "armadyl hilt");
		ITEM_ALIASES.put("arma hilt", "armadyl hilt");
		ITEM_ALIASES.put("bandos hilt", "bandos hilt");
		ITEM_ALIASES.put("sara hilt", "saradomin hilt");
		ITEM_ALIASES.put("zammy hilt", "zamorak hilt");

		// Misc equipment
		ITEM_ALIASES.put("fury orn kit", "fury ornament kit");
		ITEM_ALIASES.put("torture orn kit", "torture ornament kit");
		ITEM_ALIASES.put("anguish orn kit", "anguish ornament kit");
		ITEM_ALIASES.put("tormented orn kit", "tormented ornament kit");
		ITEM_ALIASES.put("occult orn kit", "occult ornament kit");
		ITEM_ALIASES.put("dragon def", "dragon defender");
		ITEM_ALIASES.put("dragon defender", "dragon defender");
		ITEM_ALIASES.put("rune def", "rune defender");
		ITEM_ALIASES.put("rune defender", "rune defender");
		ITEM_ALIASES.put("avernic def", "avernic defender");
		ITEM_ALIASES.put("crystal helm", "crystal helm");
		ITEM_ALIASES.put("crystal body", "crystal body");
		ITEM_ALIASES.put("crystal legs", "crystal legs");
		ITEM_ALIASES.put("crystal bow", "crystal bow");
		ITEM_ALIASES.put("crystal shield", "crystal shield");
		ITEM_ALIASES.put("crystal hally", "crystal halberd");
		ITEM_ALIASES.put("chally", "crystal halberd");

		// Misc supplies
		ITEM_ALIASES.put("anglerfish", "anglerfish");
		ITEM_ALIASES.put("anglers", "anglerfish");
		ITEM_ALIASES.put("dark crabs", "dark crab");
		ITEM_ALIASES.put("dark crab", "dark crab");
		ITEM_ALIASES.put("tuna potatoes", "tuna potato");
		ITEM_ALIASES.put("tuna potato", "tuna potato");
		ITEM_ALIASES.put("pineapple pizzas", "pineapple pizza");
		ITEM_ALIASES.put("pineapple pizza", "pineapple pizza");
		ITEM_ALIASES.put("summer pies", "summer pie");
		ITEM_ALIASES.put("summer pie", "summer pie");
		ITEM_ALIASES.put("wild pies", "wild pie");
		ITEM_ALIASES.put("wild pie", "wild pie");
		ITEM_ALIASES.put("admiral pies", "admiral pie");
		ITEM_ALIASES.put("admiral pie", "admiral pie");

		// Teleport items
		ITEM_ALIASES.put("house tabs", "teleport to house");
		ITEM_ALIASES.put("house tab", "teleport to house");
		ITEM_ALIASES.put("varrock tabs", "varrock teleport");
		ITEM_ALIASES.put("varrock tab", "varrock teleport");
		ITEM_ALIASES.put("lumby tabs", "lumbridge teleport");
		ITEM_ALIASES.put("lumby tab", "lumbridge teleport");
		ITEM_ALIASES.put("fally tabs", "falador teleport");
		ITEM_ALIASES.put("fally tab", "falador teleport");
		ITEM_ALIASES.put("cammy tabs", "camelot teleport");
		ITEM_ALIASES.put("cammy tab", "camelot teleport");
		ITEM_ALIASES.put("ardy tabs", "ardougne teleport");
		ITEM_ALIASES.put("ardy tab", "ardougne teleport");
		ITEM_ALIASES.put("dueling ring", "ring of dueling(8)");
		ITEM_ALIASES.put("glory", "amulet of glory(6)");
		ITEM_ALIASES.put("glories", "amulet of glory(6)");
		ITEM_ALIASES.put("games necklace", "games necklace(8)");
		ITEM_ALIASES.put("games neck", "games necklace(8)");
		ITEM_ALIASES.put("games necks", "games necklace(8)");
		ITEM_ALIASES.put("skills necklace", "skills necklace(6)");
		ITEM_ALIASES.put("skills neck", "skills necklace(6)");
		ITEM_ALIASES.put("combat bracelet", "combat bracelet(6)");
		ITEM_ALIASES.put("combat brace", "combat bracelet(6)");
		ITEM_ALIASES.put("wealth", "ring of wealth");
		ITEM_ALIASES.put("row", "ring of wealth");

		// Misc plurals and abbreviations
		ITEM_ALIASES.put("zenny", "zenyte shard");
		ITEM_ALIASES.put("ballista limbs", "heavy ballista limbs");
		ITEM_ALIASES.put("ballista spring", "ballista spring");
		ITEM_ALIASES.put("monkey tail", "monkey tail");
		ITEM_ALIASES.put("monkey tails", "monkey tail");
		ITEM_ALIASES.put("heavy frame", "heavy frame");
		ITEM_ALIASES.put("light frame", "light frame");
		ITEM_ALIASES.put("heavy ballista", "heavy ballista");
		ITEM_ALIASES.put("light ballista", "light ballista");
		ITEM_ALIASES.put("dfs", "dragonfire shield");
		ITEM_ALIASES.put("anti dragon shield", "anti-dragon shield");
		ITEM_ALIASES.put("anti-dragon", "anti-dragon shield");
		ITEM_ALIASES.put("explorers ring", "explorer's ring 4");
		ITEM_ALIASES.put("explorers ring 4", "explorer's ring 4");
		ITEM_ALIASES.put("seers ring", "seers ring");
		ITEM_ALIASES.put("warriors ring", "warrior ring");
		ITEM_ALIASES.put("treasonous ring", "treasonous ring");
		ITEM_ALIASES.put("tyrannical ring", "tyrannical ring");

		// Agility shortcuts
		ITEM_ALIASES.put("graceful", "graceful hood");
		ITEM_ALIASES.put("graceful hood", "graceful hood");
		ITEM_ALIASES.put("graceful top", "graceful top");
		ITEM_ALIASES.put("graceful legs", "graceful legs");
		ITEM_ALIASES.put("graceful gloves", "graceful gloves");
		ITEM_ALIASES.put("graceful boots", "graceful boots");
		ITEM_ALIASES.put("graceful cape", "graceful cape");
		ITEM_ALIASES.put("marks of grace", "mark of grace");
		ITEM_ALIASES.put("marks", "mark of grace");
		ITEM_ALIASES.put("amylase", "amylase crystal");
		ITEM_ALIASES.put("amylase crystals", "amylase crystal");

		// Ranged
		ITEM_ALIASES.put("blowpipe", "toxic blowpipe");
		ITEM_ALIASES.put("bp", "toxic blowpipe");
		ITEM_ALIASES.put("acb", "armadyl crossbow");
		ITEM_ALIASES.put("dcb", "dragon crossbow");
		ITEM_ALIASES.put("rcb", "rune crossbow");
		ITEM_ALIASES.put("msb", "magic shortbow");
		ITEM_ALIASES.put("msbi", "magic shortbow (i)");
		ITEM_ALIASES.put("magic short", "magic shortbow");
		ITEM_ALIASES.put("magic shortbow i", "magic shortbow (i)");
		ITEM_ALIASES.put("ava's", "ava's assembler");
		ITEM_ALIASES.put("avas", "ava's assembler");
		ITEM_ALIASES.put("accumulator", "ava's accumulator");
		ITEM_ALIASES.put("attractor", "ava's attractor");

		// Nex
		ITEM_ALIASES.put("torva helm", "torva full helm");
		ITEM_ALIASES.put("torva body", "torva platebody");
		ITEM_ALIASES.put("torva legs", "torva platelegs");
		ITEM_ALIASES.put("torva plate", "torva platebody");
		ITEM_ALIASES.put("torva platebody", "torva platebody");
		ITEM_ALIASES.put("torva platelegs", "torva platelegs");
		ITEM_ALIASES.put("torva full helm", "torva full helm");
		ITEM_ALIASES.put("zaryte cbow", "zaryte crossbow");
		ITEM_ALIASES.put("zcb", "zaryte crossbow");
		ITEM_ALIASES.put("zaryte crossbow", "zaryte crossbow");
		ITEM_ALIASES.put("nihil horn", "nihil horn");
		ITEM_ALIASES.put("ancient hilt", "ancient hilt");

		// Vorkath
		ITEM_ALIASES.put("vork", "vorki");
		ITEM_ALIASES.put("vorki", "vorki");

		// Hydra
		ITEM_ALIASES.put("hydra claw", "hydra's claw");
		ITEM_ALIASES.put("hydra leather", "hydra leather");
		ITEM_ALIASES.put("hydra tail", "hydra tail");
		ITEM_ALIASES.put("hydra eye", "hydra's eye");
		ITEM_ALIASES.put("hydra fang", "hydra's fang");
		ITEM_ALIASES.put("hydra heart", "hydra's heart");
		ITEM_ALIASES.put("ferocious gloves", "ferocious gloves");
		ITEM_ALIASES.put("fero gloves", "ferocious gloves");

		// Corp
		ITEM_ALIASES.put("spirit shield", "spirit shield");
		ITEM_ALIASES.put("blessed spirit shield", "blessed spirit shield");
		ITEM_ALIASES.put("holy elixir", "holy elixir");
		ITEM_ALIASES.put("sigil", "elysian sigil");
		ITEM_ALIASES.put("ely sigil", "elysian sigil");
		ITEM_ALIASES.put("arcane sigil", "arcane sigil");
		ITEM_ALIASES.put("spectral sigil", "spectral sigil");

		// Nightmare
		ITEM_ALIASES.put("nightmare staff", "nightmare staff");
		ITEM_ALIASES.put("inquisitors", "inquisitor's mace");
		ITEM_ALIASES.put("inq mace", "inquisitor's mace");
		ITEM_ALIASES.put("inq helm", "inquisitor's great helm");
		ITEM_ALIASES.put("inq top", "inquisitor's hauberk");
		ITEM_ALIASES.put("inq legs", "inquisitor's plateskirt");
		ITEM_ALIASES.put("inquisitor helm", "inquisitor's great helm");
		ITEM_ALIASES.put("inquisitor top", "inquisitor's hauberk");
		ITEM_ALIASES.put("inquisitor legs", "inquisitor's plateskirt");
		ITEM_ALIASES.put("inquisitor mace", "inquisitor's mace");
		ITEM_ALIASES.put("orb", "nightmare orb");

		// More abbreviations
		ITEM_ALIASES.put("bones to peaches", "bones to peaches");
		ITEM_ALIASES.put("b2p", "bones to peaches");
		ITEM_ALIASES.put("bonecrusher", "bonecrusher");
		ITEM_ALIASES.put("herbsack", "herb sack");
		ITEM_ALIASES.put("herb sack", "herb sack");
		ITEM_ALIASES.put("gem bag", "gem bag");
		ITEM_ALIASES.put("coal bag", "coal bag");
		ITEM_ALIASES.put("seed box", "seed box");
		ITEM_ALIASES.put("rune pouch", "rune pouch");
		ITEM_ALIASES.put("looting bag", "looting bag");
		ITEM_ALIASES.put("loot bag", "looting bag");

		// Bonds
		ITEM_ALIASES.put("bond", "old school bond");
		ITEM_ALIASES.put("bonds", "old school bond");

		// Amulets
		ITEM_ALIASES.put("rancour", "amulet of rancour");
	}

	// Items that should be skipped in favor of their aliases
	// (e.g., "scythe" should match "scythe of vitur", not the basic "Scythe" item)
	private static final Set<String> ALIAS_PRIORITY_ITEMS = new HashSet<>();
	static
	{
		ALIAS_PRIORITY_ITEMS.add("scythe");
	}

	// Map of lowercase item name -> item ID
	private final Map<String, Integer> itemNameToId = new HashMap<>();

	// List of item names sorted by length (longest first) for matching
	private final List<String> sortedItemNames = new ArrayList<>();

	// Set of all single-word item names for O(1) lookup
	private final Set<String> singleWordItems = new HashSet<>();

	// Map of first word -> list of multi-word item names starting with that word (sorted by length, longest first)
	private final Map<String, List<String>> multiWordItemsByFirstWord = new HashMap<>();

	// Alias lookup structures (same pattern as item names)
	private final Set<String> singleWordAliases = new HashSet<>();
	private final Map<String, List<String>> multiWordAliasesByFirstWord = new HashMap<>();

	// Set of filtered item names (lowercase) from config
	private final Set<String> filteredItemNames = new HashSet<>();

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

	@Inject
	private ItemLinkDataLoader ItemLinkDataLoader;

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
		updateFilteredItems();

		// Load OSRSBox item data (examine texts, icons)
		ItemLinkDataLoader.loadItems();

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
		singleWordAliases.clear();
		multiWordAliasesByFirstWord.clear();
		filteredItemNames.clear();
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

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals("itemlink"))
		{
			return;
		}

		if (event.getKey().equals("filteredItems"))
		{
			updateFilteredItems();
		}
	}

	/**
	 * Parse the filtered items config string and update the cached set.
	 */
	private void updateFilteredItems()
	{
		filteredItemNames.clear();
		String filterString = config.filteredItems();
		if (filterString == null || filterString.isEmpty())
		{
			return;
		}

		String[] items = filterString.split(",");
		for (String item : items)
		{
			String trimmed = item.trim().toLowerCase();
			if (!trimmed.isEmpty())
			{
				filteredItemNames.add(trimmed);
			}
		}
		log.debug("Updated item filter with {} items", filteredItemNames.size());
	}

	/**
	 * Check if an item should be filtered (excluded from linking).
	 */
	private boolean isItemFiltered(String itemNameLower, int itemId)
	{
		// Check name filter
		if (filteredItemNames.contains(itemNameLower))
		{
			return true;
		}

		// Check price filters
		int minValue = config.minimumItemValue();
		int maxValue = config.maximumItemValue();

		if (minValue > 0 || maxValue > 0)
		{
			int gePrice = itemManager.getItemPrice(itemId);

			if (minValue > 0 && gePrice < minValue)
			{
				return true;
			}

			if (maxValue > 0 && gePrice > maxValue)
			{
				return true;
			}
		}

		return false;
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

		// Build optimized lookup structures for item names
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

		// Build optimized lookup structures for aliases
		List<String> sortedAliases = new ArrayList<>(ITEM_ALIASES.keySet());
		sortedAliases.sort((a, b) -> Integer.compare(b.length(), a.length()));

		for (String alias : sortedAliases)
		{
			// Only include aliases that map to valid items
			String targetItem = ITEM_ALIASES.get(alias);
			if (!itemNameToId.containsKey(targetItem))
			{
				continue;
			}

			int spaceIndex = alias.indexOf(' ');
			if (spaceIndex == -1)
			{
				// Single-word alias
				singleWordAliases.add(alias);
			}
			else
			{
				// Multi-word alias - index by first word
				String firstWord = alias.substring(0, spaceIndex);
				multiWordAliasesByFirstWord
					.computeIfAbsent(firstWord, k -> new ArrayList<>())
					.add(alias);
			}
		}

		itemsLoaded = true;
		log.info("Loaded {} item names for Item Link ({} single-word, {} multi-word prefixes, {} aliases)",
			itemNameToId.size(), singleWordItems.size(), multiWordItemsByFirstWord.size(),
			singleWordAliases.size() + multiWordAliasesByFirstWord.values().stream().mapToInt(List::size).sum());
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

			// First, check if this is a money amount (e.g., 1m, 24m, 500k, 1b)
			Matcher moneyMatcher = MONEY_PATTERN.matcher(currentWord);
			if (moneyMatcher.matches())
			{
				String formattedMoney = formatMoneyAmount(moneyMatcher.group(1), moneyMatcher.group(2));
				if (formattedMoney != null)
				{
					// Check if coins should be filtered
					if (!isItemFiltered("coins", COINS_ITEM_ID))
					{
						// Get color based on the gp amount, not item value
						long gpAmount = parseGpAmount(moneyMatcher.group(1), moneyMatcher.group(2));
						String color = getGpColor(gpAmount);
						result.append(String.format("<col=%s>%s</col><col=%s>", color, formattedMoney, COLOR_PUBLIC_CHAT));

						i += currentWord.length();
						continue;
					}
				}
			}

			String matchedItemName = null;
			String matchedAlias = null;

			// Check for multi-word items starting with this word (longest first)
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
			// Skip items that have alias priority (e.g., "scythe" should match alias instead)
			if (matchedItemName == null && singleWordItems.contains(currentWord) && !ALIAS_PRIORITY_ITEMS.contains(currentWord))
			{
				matchedItemName = currentWord;
			}

			// If no item name match, check for multi-word aliases
			if (matchedItemName == null)
			{
				List<String> multiWordAliasCandidates = multiWordAliasesByFirstWord.get(currentWord);
				if (multiWordAliasCandidates != null)
				{
					for (String candidate : multiWordAliasCandidates)
					{
						if (i + candidate.length() > lowerMessage.length())
						{
							continue;
						}

						if (!lowerMessage.regionMatches(i, candidate, 0, candidate.length()))
						{
							continue;
						}

						int endPos = i + candidate.length();
						if (endPos < lowerMessage.length() && Character.isLetterOrDigit(lowerMessage.charAt(endPos)))
						{
							continue;
						}

						matchedAlias = candidate;
						matchedItemName = ITEM_ALIASES.get(candidate);
						break;
					}
				}
			}

			// If still no match, check for single-word aliases
			if (matchedItemName == null && singleWordAliases.contains(currentWord))
			{
				matchedAlias = currentWord;
				matchedItemName = ITEM_ALIASES.get(currentWord);
			}

			if (matchedItemName != null)
			{
				int itemId = itemNameToId.get(matchedItemName);

				// Check if item should be filtered out
				if (isItemFiltered(matchedItemName, itemId))
				{
					result.append(message.charAt(i));
					i++;
					continue;
				}

				// Determine what was actually matched in the message and what to display
				String displayName;
				int matchLength;

				if (matchedAlias != null)
				{
					// Matched an alias - display the real item name, advance by alias length
					displayName = capitalizeItemName(matchedItemName);
					matchLength = matchedAlias.length();
				}
				else
				{
					// Matched the real item name - preserve original case from message
					displayName = message.substring(i, i + matchedItemName.length());
					matchLength = matchedItemName.length();
				}

				String color = getItemColor(itemId);
				result.append(String.format("<col=%s>%s</col><col=%s>", color, displayName, COLOR_PUBLIC_CHAT));

				// Add to overlay for tooltip
				itemLinkOverlay.addRecentItem(itemId, 1);

				i += matchLength;
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

	/**
	 * Format a money amount like "1m" or "500k" into a readable string.
	 * Returns the formatted amount (e.g., "1M", "500K", "1.5B") or null if invalid.
	 */
	private String formatMoneyAmount(String numberPart, String suffix)
	{
		try
		{
			double value = Double.parseDouble(numberPart);
			if (value <= 0)
			{
				return null;
			}

			String upperSuffix = suffix.toUpperCase();

			// Format nicely - keep decimal if present, otherwise show as integer
			if (value == Math.floor(value))
			{
				return String.format("%d%s", (long) value, upperSuffix);
			}
			else
			{
				// Remove trailing zeros from decimal
				String formatted = String.format("%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
				return formatted + upperSuffix;
			}
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * Parse a money amount string into actual GP value.
	 */
	private long parseGpAmount(String numberPart, String suffix)
	{
		try
		{
			double value = Double.parseDouble(numberPart);
			switch (suffix.toLowerCase())
			{
				case "k":
					return (long) (value * 1_000);
				case "m":
					return (long) (value * 1_000_000);
				case "b":
					return (long) (value * 1_000_000_000);
				default:
					return (long) value;
			}
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/**
	 * Get color for GP amount display (based on wealth tiers).
	 */
	private String getGpColor(long gpAmount)
	{
		if (gpAmount >= 1_000_000_000) // 1B+
		{
			return COLOR_LEGENDARY; // Orange
		}
		else if (gpAmount >= 100_000_000) // 100M+
		{
			return COLOR_EPIC; // Purple
		}
		else if (gpAmount >= 10_000_000) // 10M+
		{
			return COLOR_RARE; // Blue
		}
		else if (gpAmount >= 1_000_000) // 1M+
		{
			return COLOR_UNCOMMON; // Green
		}
		else
		{
			return COLOR_GOLD; // Gold color for under 1M
		}
	}

	/**
	 * Capitalize an item name properly (title case with special handling for apostrophes).
	 */
	private String capitalizeItemName(String name)
	{
		if (name == null || name.isEmpty())
		{
			return name;
		}

		StringBuilder result = new StringBuilder();
		boolean capitalizeNext = true;

		for (int i = 0; i < name.length(); i++)
		{
			char c = name.charAt(i);

			if (Character.isWhitespace(c))
			{
				capitalizeNext = true;
				result.append(c);
			}
			else if (c == '\'')
			{
				// Don't capitalize after apostrophe (e.g., "Osmumten's" not "Osmumten'S")
				result.append(c);
			}
			else if (capitalizeNext)
			{
				result.append(Character.toUpperCase(c));
				capitalizeNext = false;
			}
			else
			{
				result.append(c);
			}
		}

		return result.toString();
	}
}

