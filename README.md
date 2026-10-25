# Clue Helper

Clue Helper finds the fastest way to every clue step from the teleports you actually own, then guides you there
click by click. It runs alongside RuneLite's built-in **Clue Scroll** plugin: Clue Scroll tells you what the step is,
Clue Helper tells you how to get there quickest.

## Getting started

1. Keep the **Clue Scroll** plugin turned on. Clue Helper reads the current step from it.
2. Turn on **Clue Helper** and read a clue.
3. Open your bank once, so banked teleports and items count.

A small **Clue Helper** box appears under Clue Scroll's: what to do now, the items it needs, and the step after it.

## Features

- **Routes from what you own.** Teleports in your inventory, worn or in your bank, your spellbook, your house
  (portal nexus, jewellery box, fairy ring, spirit tree, mounted items, altar), fairy rings, spirit trees, quetzals,
  boats, charters, minecarts, gliders, the master scroll book and more. Charges you have checked are counted.
- **Bank stops only when they pay off.** A route through the bank is picked only when it is clearly faster than
  going straight there. The box lists what to withdraw, and what to deposit first if your inventory is full.
- **Spellbook changes.** Routes can switch spellbook on the way when that is faster, if you have the runes.
- **Step-by-step guidance.** The item, spell, menu line or teleport option to click is highlighted, and so is the
  fairy ring code. A path line leads you to the next spot and outlines the doors, stairs and ladders on the way.
  Objects whose left click does the job are filled in, and Menu Entry Swapper's swaps are taken into account.
- **Every step type.** Emote steps with their items and STASH units, Charlie's items, Sherlock's skill tasks,
  steps that need a key from a monster, and gear to wear on arrival.
- **Hot/cold planner.** For beginner and master hot/cold steps it suggests where to feel the strange device next,
  from your feels so far, and routes you there.

## Settings

| Setting | What it does                                                          | Default |
|---|-----------------------------------------------------------------------|---|
| Avoid Wilderness | Leave out Wilderness routes for steps outside it                      | Always |
| Scroll book stays out of Wilderness | Bank the master scroll book before a teleport into the wilderness     | On |
| Use the bank | Count banked teleports and items, and GIM group storage               | On |
| Spellbook changes | Which spellbook changes routes may use | All |
| Highlight items / spells / menu lines | What gets highlighted for the current action                          | On |
| Path | Draw the path line to where to go next                                | On |
| Show the box | Show the Clue Helper box under Clue Scroll's                          | On |
| Show the next step | Show the action after the current one in the box                      | On |
| Probe planner | Suggest where to feel next on hot/cold steps                          | On |

## Good to know

- Clue Helper stays off on seasonal, Deadman, beta and other special worlds.
- On free-to-play worlds, a path can still lead through members-only areas and shortcuts. (soon to be fixed)
- Routes don't use your own Sailing boat yet; Sailing-only spots show a note to sail there.

## Privacy

Clue Helper makes no network calls. What it remembers (your bank, house, STASH units, grown spirit trees and similar)
is saved in your RuneLite profile like any other plugin setting. It reads, without changing anything, the Clue
Scroll plugin's current clue, Menu Entry Swapper's swaps and Time Tracking's farming patches (skill challenges).

## How it works

The teleport catalog, the walk table and the step data are bundled in the plugin and generated from the game cache
and Shortest Path's transport data. A route search ranks every way from where you stand to the step by game ticks.
The data is regenerated when a game update changes clue steps or transports.

## Credits

Teleport data, the world collision map and the walking rules are derived from
[Shortest Path](https://github.com/Skretzo/shortest-path) (BSD 2-Clause); see [NOTICE](NOTICE).