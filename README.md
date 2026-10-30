<h1>Clue Helper</h1>

<p>
  <b>A click-by-click guide to every clue step, routed from the teleports you own.</b>
</p>

<p>
  <a href="https://runelite.net/plugin-hub/show/clue-helper"><img src="https://img.shields.io/endpoint?url=https://api.runelite.net/pluginhub/shields/installs/plugin/clue-helper" alt="Plugin Hub installs"></a>
  <a href="https://github.com/NathanCollaert/Clue-Helper/issues/new?template=route-report.yml"><img src="https://img.shields.io/badge/report-slow%20route-orange" alt="Report a slow route"></a>
  <a href="https://github.com/NathanCollaert/Clue-Helper/issues/new?template=bug-report.yml"><img src="https://img.shields.io/badge/report-bug-red" alt="Report a bug"></a>
</p>

Clue Helper runs alongside RuneLite's built-in **Clue Scroll** plugin. Clue Scroll tells you *what* the step is; Clue
Helper tells you the quickest way to *get there* with what's in your inventory, your bank, your house and your
spellbook, and highlights every click on the way.


## Getting started

1. Install **Clue Helper** from the Plugin Hub and keep RuneLite's **Clue Scroll** plugin turned on.
2. Read a clue.
3. Open your bank once, so banked teleports and items count too.

A small **Clue Helper** box appears under Clue Scroll's: what to do now, the items it needs, and the step after it.


## What it does

| | |
|---|---|
| 🧭 **Routes** | Teleports carried, worn or banked, your spellbook, your house (portal nexus, jewellery box, fairy ring, spirit tree, mounted items, altar), fairy rings, spirit trees, quetzals, boats, charters, minecarts, gliders, the master scroll book and more. Charges you've checked count. |
| 🏦 **Bank stops** | A route through the bank is picked only when it beats going straight there. The box lists what to withdraw (and what to deposit first when you're full), and the bank opens on a tab with just those items. |
| 📖 **Spellbook** | Routes switch spellbook on the way when that's faster and you have the runes. |
| 🖱️ **Guidance** | The item, spell, menu line, teleport option or fairy ring code to click is highlighted. A path line leads you on and outlines the doors, stairs and ladders on the way. Menu Entry Swapper's swaps are taken into account. |
| 📜 **Every type** | Emotes with their items and STASH units, Charlie's items, Sherlock's skill tasks, keys dropped by monsters, gear to wear on arrival. Ways in are guided too: the trapdoor to open, the rope to use on a crevice, the Abyss obstacle to cross and the chat options to pick. |
| 🔥 **Hot/cold** | On beginner and master hot/cold steps it suggests where to feel the strange device next, from your feels so far, and routes you there. Once the spot is known, it routes you to the dig from anywhere. |


## Report a slow route or a bug

Think you have a faster way than the one Clue Helper picked, or it guided you wrong? Reporting takes ten seconds:

1. Stay on the step (where you were when the route was shown).
2. **Right-click the Clue Helper box** and choose **Report route**.
3. A GitHub issue form opens and the report is already on your clipboard: paste it (Ctrl+V) and say which way you'd
   have taken.

The report holds the step, where you stand, your settings, every route Clue Helper considered with its time, the
teleports landing near the step with whether it thinks you have them (and if not, why), and a compressed copy of
what the route was planned from, so the exact plan can be replayed and fixed. That copy includes your skill levels,
quests, what you carry and wear, and only those banked items a route could use: teleports, runes, tools for shortcuts
and the step's own items. It never includes the rest of your bank, your username or your login.

Something else wrong? [Open a bug report](https://github.com/NathanCollaert/Clue-Helper/issues/new?template=bug-report.yml).


## Settings

| Setting | What it does | Default |
|---|---|---|
| Avoid Wilderness | Leave out Wilderness routes for steps outside it | Always |
| Scroll book stays out of Wilderness | Bank the master scroll book before a teleport into the Wilderness | On |
| Use the bank | Count banked teleports and items, and GIM group storage | On |
| Spellbook changes | Which spellbook changes routes may use | All |
| Highlight items / spells / menu lines | What gets highlighted for the current action | On |
| Withdraw tab | Open a bank tab with only the items to withdraw (uses Bank Tags) | On |
| Path | Draw the path line to where to go next | On |
| Show the box | Show the Clue Helper box under Clue Scroll's | On |
| Show the next step | Show the action after the current one in the box | On |
| Probe planner | Suggest where to feel next on hot/cold steps | On |


## Good to know

- Clue Helper stays off on seasonal, Deadman, beta and other special worlds.
- On free-to-play worlds, a path can still lead through members-only areas and shortcuts.
- Routes don't use your own Sailing boat yet; Sailing-only spots show a note to sail there.


## Privacy

Clue Helper makes no network calls. What it remembers (your bank, house, STASH units, grown spirit trees, unlocks such as Xeric's Honour and similar)
is saved in your RuneLite profile like any other plugin setting. It reads, without changing anything, the Clue Scroll
plugin's current clue, Menu Entry Swapper's swaps and Time Tracking's farming patches. A route report only leaves
your computer when you paste it somewhere yourself.


## How it works

The teleport catalog, the walk table and the step data are bundled in the plugin, generated from the game cache and
Shortest Path's transport data. A route search ranks every way from where you stand to the step by game ticks, using
times measured in game. The data is regenerated when a game update changes clue steps or transports.


## Credits

Teleport data, the world collision map and the walking rules are derived from
[Shortest Path](https://github.com/Skretzo/shortest-path) (BSD 2-Clause); see [NOTICE](NOTICE).
