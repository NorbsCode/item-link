# Item Link Plugin for RuneLite

A World of Warcraft-style item linking plugin for Old School RuneScape. Item names in chat are automatically highlighted with colored links that other plugin users can hover over to see detailed item information.

## Features

- **Automatic Item Detection**: Just type item names naturally in chat - they'll be automatically highlighted!
- **WoW-Style Rarity Colors**: Items are colored based on their GE value:
  - White: Under 10K
  - Green: 10K+
  - Blue: 100K+
  - Purple: 1M+
  - Orange: 10M+
- **Detailed Hover Tooltips**: Hover over linked items to see:
  - GE Price (total and per item)
  - High Alch Value
  - Buy Limit
  - Equipment Stats (attack/defence bonuses, strength, prayer, etc.)
  - Equipment Slot
  - Weight
- **Works Everywhere**: Public chat, friends chat, clan chat, private messages
- **Overhead Text**: Item links are formatted in overhead speech bubbles too
- **Case-Insensitive**: Works whether you type "Abyssal whip", "abyssal whip", or "ABYSSAL WHIP"

## How to Use

Simply type naturally! The plugin automatically detects item names in your chat messages.

**Examples:**
- Type: `I just got an Abyssal whip!`
- Shows as: `I just got an [Abyssal whip]!` (with color)

<img width="524" height="289" alt="image" src="https://github.com/user-attachments/assets/56299a11-c2ac-48dc-a117-3388a146ec7c" />

- Type: `Selling Dragon scimitar for 100k`
- Shows as: `Selling [Dragon scimitar] for 100k` (with color)

- Type: `Anyone want to buy Bandos chestplate?`
- Shows as: `Anyone want to buy [Bandos chestplate]?` (with color)

**Hover over any highlighted item** to see detailed information including GE price, stats, and more!

## How It Works

1. The plugin loads all OSRS item names when you log in
2. When you (or anyone else) sends a chat message, it scans for item names
3. Recognized items are highlighted with colored brackets based on their value
4. Other players with this plugin installed will see the same highlights
5. Players without the plugin see your normal, unmodified text

## Configuration

- **Color by Rarity**: Toggle WoW-style rarity colors on/off (default: on)

## Notes

- Only item names with 5+ characters are detected to avoid false positives
- The plugin uses word boundaries, so "Dragon" inside "Dragonfire" won't match
- Longer item names take priority (e.g., "Dragon scimitar" matches before "Dragon")
- This is a client-side modification - other players need the plugin to see highlights

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
