# Slayer Task Loot

Tracks the loot from your current slayer task on its own, so you can see what a task earned without wiping your lifetime loot history for that monster.

The built-in Loot Tracker only stores one running total per NPC, so answering "what did this task give me" means resetting everything you've ever recorded for it. This keeps a separate per-task tally alongside it, and can subtract the supplies you used.

## What it does

- One record per task, with every drop credited to it, highest value first
- Kill count and time actually spent on task
- Only counts kills that belong to your task — superior monsters and boss variants count, Konar tasks only count kills in the assigned area, and stray kills are ignored
- Keeps the task open for a couple of minutes after the last kill so loot you have to collect, like Araxxor's, still lands on it
- Optional profit after supplies, with a breakdown of what you spent
- History of completed tasks, kept per character and reloaded on login

A task in progress survives logging out, world hopping and toggling the plugin off.

## Profit after supplies

Optional, and off-switchable — turn off **Track supplies and profit** and it becomes a pure drop tracker with no measurement at all.

```
▾ Drops                        1.4M gp
    <your drops>
▾ Supplies                    -412k gp
    Prayer potion   x14 doses    210k
    Toxic blowpipe  x820         98k
    Shark           x22          77k
    Varrock teleport x3          27k
Profit                          988k gp
```

Supplies are measured from your inventory, worn equipment and rune pouch, so food, potions, runes, ammunition and teleports are all covered (assuming a session is in progress) without a list of items to maintain. Potions are counted in **doses**, not bottles.

Charged weapons appear as a single row named after the weapon. Hover it to see what its charges cost.

Things that aren't consumption don't get charged: banking, shops, trades, the Grand Exchange, dying, dropping an item, or setting up and picking up a cannon.

By default, drops and supplies of the same item cancel out — five sharks dropped and two eaten shows three sharks and no shark cost. Turn off **Net matching drops** to see both. Potions cancel too, in doses. A `Prayer potion(3)` dropped and two doses drunk shows a `Prayer potion(1)` in Drops and no prayer potion cost — the container you're actually left holding. Drops and doses are pooled per potion, so the remainder is repacked into the largest containers that hold it; five doses left over read as a `(4)` and a `(1)`.

Bones and ashes are drops rather than supplies by default, since prayer experience is the point of them. Turn on **Count bones and ashes used** and any you bury, scatter or offer are counted as spent, so netting cancels them against the drop they came from — including the ones a bonecrusher or ash sanctifier takes before you ever see them.

Supplies only count while a session is open. A session starts on your first credited kill, stays open while kills keep happening, and closes after an idle timeout — so an unrelated boss trip doesn't land on your task's bill. There's a grace window before the first kill so your teleport out and pre-fight boosts still count. You can also start, end, resume and merge sessions from the panel manually.

## Charged items

| Item | Cost counted |
|---|---|
| Scythe of Vitur | 2 blood runes and 1/100 vial of blood per attack |
| Tumeken's shadow | 2 soul and 5 chaos runes per cast |
| Sanguinesti staff | 2 blood runes per cast |
| Trident of the seas | 1 chaos, 1 death, 5 fire runes and 10 gp per cast |
| Trident of the swamp | 1 chaos, 1 death, 5 fire runes and 1 Zulrah's scale per cast |
| Warped sceptre | 2 chaos and 5 earth runes per cast |
| Venator bow | 1 ancient essence per shot |
| Eye of ayak | 1 demon tear, or 2 death and 1 chaos runes, per cast |
| Abyssal tentacle | 1 abyssal whip per 10,000 attacks |
| Toxic blowpipe | darts at your Ava's device's rate, and 2 Zulrah's scales per 3 attacks |
| Tome of fire, water, earth | 1 page per 20 casts |
| Dizana's quiver | 1 sunfire splinter per 3 shots |
| Dwarf multicannon | 1 cannonball per shot |

Confirmed working in game: scythe, Tumeken's shadow, sanguinesti staff, trident of the seas, venator bow, eye of ayak, abyssal tentacle, toxic blowpipe, tome of fire, Dizana's quiver, cannonballs, arrows and bolts, and runes from both the inventory and a rune pouch.

Implemented but not yet confirmed in game: trident of the swamp, enchanted trident of the seas, warped sceptre, tomes of water and earth, and the eye of ayak's special attack.

## Caveats

**Some costs are averages, not measurements.** Blowpipe scales, Dizana's quiver splinters and blowpipe darts are all consumed randomly, so they're billed at their long-run rate. Over a full task these land close to reality; over a dozen attacks they won't match what the item's charge counter actually did. That's expected, not a fault.

**Recharging an item mid-task is billed twice** — once for the runes or scales leaving your inventory, and again as the charges get used. Recharge at a bank, where restocking isn't counted at all, and it's billed once.

**Blowpipe darts need the blowpipe checked once per login** before they can be counted, since that's the only time the game says which dart is loaded. Set **Blowpipe dart** in the config to skip that.

**Untradeable items are worth 0 gp**, so crystal equipment and similar cost nothing.

**Not tracked:** serpentine helm and toxic staff of the dead. Both spend scales by time in combat rather than per attack, which isn't reliably measurable.

**Slayer chests aren't included.** Brimstone and Larran's chest contents aren't monster drops, so they don't appear here — the built-in Loot Tracker records those separately.

## Settings

### Display

| Setting | Default | Description |
|---|---|---|
| Show item values | On | Show each item's value next to its quantity |
| Tasks to remember | 10 | How many finished tasks to keep in History (0–50) |

### Loot

| Setting | Default | Description |
|---|---|---|
| Count loot | Dropped | Count every drop, or only what you pick up (including direct-to-container loot) |
| Late loot window | 120s | How long after a kill its drop can still count, for bosses whose loot has to be collected (0–600) |

### Supplies

| Setting | Default | Description |
|---|---|---|
| Track supplies and profit | On | Off makes this a pure drop tracker |
| Net matching drops | On | Cancel drops against supplies of the same item |
| Count bones and ashes used | Off | Count bones and ashes you bury, scatter or offer as supplies, so netting cancels them against their drop |
| End session after | 10 min | Idle time before supplies stop counting toward the task (1–60) |
| Grace window | 30s | How long before your first kill supplies still count (0–300) |

### Charged items

For items the game gives no way to identify. Each defaults to reading it from the message shown when you fill the item.

| Setting | Description |
|---|---|
| Eye of ayak | Demon tears, or death and chaos runes |
| Blowpipe dart | Which dart is loaded |
| Cannonball | Regular or granite |
| Tome of fire page | Burnt or searing — the game gives no way to tell these apart, so this one has no automatic option |

## License

BSD 2-Clause — see [LICENSE](LICENSE).
