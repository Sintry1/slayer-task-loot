# Slayer Task Loot

A RuneLite plugin that tracks the loot from your current slayer task on its own, so you can see what a task earned without resetting your lifetime loot history for that monster.

The built-in Loot Tracker only offers per-monster **Reset** and **Reset All**, and stores one cumulative total per NPC. Seeing "what did this task give me" there means wiping everything you've ever recorded for that monster. This keeps a separate, per-task tally alongside it, and optionally subtracts the supplies you burned getting it.

## Features

### Per-task loot
- One record per assignment, with a running total of every drop credited to it
- Items listed highest-value first, with quantity and GE value
- Kill count and time actually spent on task

### Only kills that counted
Drops are attributed using the slayer counter itself: a kill is credited to the task only when `SLAYER_COUNT` decrements. That means the tally automatically gets the awkward cases right, with no monster-name lists to maintain:

- **Superior slayer monsters** count, because the game credits them
- **Boss-variant tasks** count (Kree'arra on an Aviansies task, Vorkath on Blue dragons)
- **Konar tasks** only count kills made in the assigned area, exactly as the game does
- Stray kills of things that aren't your task are ignored

Drops are recorded when the monster **drops** them, not when you pick them up — the event comes from the game's own drop-log script. Leave loot on the ground and it still counts.

### Loot you have to collect
Some monsters don't drop loot as they die; Araxxor leaves it to be gathered afterwards. A **late loot window** (2 minutes by default) keeps the task open so those drops still land on it, including when the kill was the one that finished the assignment.

Late drops are only credited when they come from a monster already **confirmed as a target of this task** — learned by watching what dies on the ticks the slayer counter moves. Kill credit is recorded per tick rather than per NPC, so without that name check a wide window would sweep up any drop that happened to land inside it. With it, widening the window doesn't pull in stray kills.

### Net profit after supplies
Optionally shows profit rather than gross drop value, subtracting what you used getting the kills, with a breakdown of what went:

```
Drops                    1.4M gp
Supplies                -412k gp
   Prayer potion  x14 doses    210k gp
   Adamant bolts  x820          98k gp
   Shark          x22           77k gp
   Varrock teleport x3          27k gp
Profit                   988k gp
```

Supply cost is measured by diffing your **inventory, worn equipment and rune pouch** and charging the loss in GE value — one rule that covers food, potions, runes, ammunition, cannonballs and teleports at once, with no item table to keep up to date.

Losses are totalled per item family, so **dose-based potions are counted in doses, not bottles**. Drinking one dose is a 4-dose leaving and a 3-dose arriving, which costs the difference between the two rather than a whole potion. Fourteen sips read as `x14 doses`, not `x14 Prayer potion(4)`. The vial returned by the last dose nets the same way.

The breakdown always sums to the total beside it, because both come from the same per-family diff.

Item movement that can't be consumption is re-baselined instead of charged, so none of the following show up as supply cost:

- Banking, deposit boxes, shops, trades and the Grand Exchange
- Dying and losing your inventory
- Explicitly dropping an item

### Sessions
Supplies have no equivalent of the kill counter — nothing in game state says a potion was drunk *for the task*. Charging everything while a task is open would put an unrelated boss trip on the task's bill, so usage is attributed by session instead:

- A session **opens** on a credited kill
- It **stays open** while kills keep happening
- It **closes** after the configured idle timeout, or when the task changes

Supplies only count while a session is open, plus a **grace window** before it opens so the teleport out and the boost drunk before the first kill are included.

Each task shows its **session count** and **on-task time** so the attribution can be checked against what you actually did, rather than taken on trust.

### History
Completed tasks are kept per character and reloaded on login, with a configurable number to remember. A task in progress survives logging out, world hopping and toggling the plugin.

## Configuration

### Display

| Setting | Default | Description |
|---|---|---|
| Value shown | Net profit after supplies | Gross drop value, or that figure minus supplies used on task |
| Show item values | On | Show the GE value next to each item's quantity |
| Tasks to remember | 10 | How many completed tasks to keep in History (0–50) |

### Loot

| Setting | Default | Description |
|---|---|---|
| Late loot window (seconds) | 120 | How long after a task kill its drop can still arrive and be credited, for monsters whose loot has to be collected (0–600). 0 credits only drops that land as the monster dies |

### Supplies

| Setting | Default | Description |
|---|---|---|
| End session after (minutes) | 5 | Idle time with no credited kill before supplies stop counting toward the task (1–60) |
| Grace window (seconds) | 30 | How far before a session opens supplies still count, for teleports and pre-fight boosts (0–300) |

## What it doesn't track

Item charges leave no trace in inventory or equipment state, so they can't be seen by this approach and are **not** counted as supply cost:

- Blowpipe scales, powered staves and tridents
- Ruinous Powers
- Cannon setup

Items with no GE price — most untradeables, including crystal equipment — are valued at zero, so they cost nothing.

**Slayer chest loot is out of scope by design.** Brimstone Chest and Larran's chests aren't monster drops — the game treats them as separate chest events — so their contents don't appear here. The keys themselves are untradeable and price at zero. Track those with the built-in Loot Tracker, which records them as their own entries.

## Dependencies

None. Slayer state is read from the game's own varps and loot from `ServerNpcLoot`, so there's no `@PluginDependency` on the Slayer or Loot Tracker plugins and nothing breaks if either is switched off.

## License

BSD 2-Clause — see [LICENSE](LICENSE).
