# Item Link Plugin for RuneLite

A World of Warcraft-style item linking plugin for Old School RuneScape. Item names in chat are automatically highlighted with colored links that other plugin users can hover over to see detailed item information.

## Features

- **Automatic Item Detection**: Just type item names naturally in chat - they'll be automatically highlighted!
- **Smart Abbreviations**: Common abbreviations like "tbow", "ags", "bcp", "scythe", "bond", etc. are recognized and link to the correct items
- **GP Amount Detection**: Money amounts like "1m", "500k", "2.5b" are automatically highlighted with value-based coloring
- **WoW-Style Rarity Colors**: Items are colored based on their GE value:
  - White: Under 10K
  - Green: 10K+
  - Blue: 100K+
  - Purple: 1M+
  - Orange: 10M+
- **Detailed Hover Tooltips**: Hover over linked items to see:
  - Item icon
  - Examine text
  - GE Price (total and per item)
  - GE Buy Limit
  - High/Low Alch values with spell icons
  - Equipment Stats with skill icons (attack/defence bonuses, strength, prayer, etc.)
  - Equipment Slot and Attack Speed
  - Weight
  - Members indicator (P2P)
  - Untradeable indicator
- **Multiple Item Support**: When multiple items are mentioned on the same line, each gets its own tooltip displayed side-by-side
- **Works Everywhere**: Public chat, friends chat, clan chat, private messages
- **Overhead Text**: Item links are formatted in overhead speech bubbles too
- **Case-Insensitive**: Works whether you type "Abyssal whip", "abyssal whip", or "ABYSSAL WHIP"
- **Filtering Options**: Filter out items by name or value range

## How to Use

Simply type naturally! The plugin automatically detects item names in your chat messages.

**Examples:**
- Type: `I just got an Abyssal whip!`
- Shows as: `I just got an Abyssal whip!` (with the item name colored)

<img width="602" height="274" alt="image" src="https://github.com/user-attachments/assets/44a3fb0a-d733-4761-ba14-dcf37e3e8eeb" />

- Type: `Selling tbow for 1.2b`
- Shows as: `Selling Twisted bow for 1.2B` (with colors - tbow expands to full name)

- Type: `Anyone want to buy bcp and tassets?`
- Shows as: `Anyone want to buy Bandos chestplate and Bandos tassets?` (with colors)

**Hover over any highlighted item** to see detailed information including GE price, stats, and more!

## Supported Abbreviations

The plugin recognizes hundreds of common OSRS item abbreviations including:
- **Weapons**: tbow, bp, ags, bgs, sgs, zgs, dwh, dclaws, scythe, sang, rapier, fang, shadow, kodai, harm, bowfa/bofa, acb, zcb, dcb, etc.
- **Armor**: bcp, tassets, prims, pegs, eternals, serp helm, torva, ancestral, masori, etc.
- **Jewelry**: fury, torture, anguish, tormented, occult, suffering, rancour, etc.
- **Potions**: brews, restores, ppots, stams, scb, divine, etc.
- **Materials**: addy bars, rune ore, dragon bones, nats, bloods, etc.
- **Misc**: bond, graceful, looting bag, rune pouch, etc.

## Configuration

- **Color by Rarity**: Toggle WoW-style rarity colors on/off (default: on)
- **Filtered Items**: Comma-separated list of item names to exclude from linking
- **Minimum Item Value**: Only link items worth at least this much GP
- **Maximum Item Value**: Only link items worth at most this much GP

## How It Works

1. The plugin loads all OSRS item names and data when you log in
2. When you (or anyone else) sends a chat message, it scans for item names and abbreviations
3. Recognized items are highlighted with colored brackets based on their value
4. Other players with this plugin installed will see the same highlights
5. Players without the plugin see your normal, unmodified text

## Notes

- Only item names with 5+ characters are detected to avoid false positives
- The plugin uses word boundaries, so "Dragon" inside "Dragonfire" won't match
- Longer item names take priority (e.g., "Dragon scimitar" matches before "Dragon")
- This is a client-side modification - other players need the plugin to see highlights
- Item data is fetched from the OSRS Wiki API and cached locally

## Installation

This plugin is available on the RuneLite Plugin Hub. Search for "Item Link" in the Plugin Hub to install.

## Building

```bash
./gradlew build
```

## Support

If you encounter any issues or have suggestions, please open an issue on GitHub.

## License

BSD 2-Clause License - See LICENSE file for details.
