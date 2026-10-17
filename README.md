# coloniesPlus

An add-on for [MineColonies](https://www.curseforge.com/minecraft/mc-mods/minecolonies) that fixes some of its most common annoyances: builders stuck waiting for materials they could make themselves, citizens standing in cactus and fire or stuck inside blocks, bowls and bottles piling up where nobody uses them, and slow couriers.

> **Early test build (0.2.0).** Try it in a test world or back up your world first.

## Features

### Builders convert spare materials

When a builder is stuck waiting for a block that nobody else in the colony can supply, it can make that block from materials in its hut, racks or inventory that the current build does not need.

- **Swap material types.** Spruce planks for oak planks, spruce logs for oak logs (stripped for stripped, wood for wood), one stone for another, white wool for red wool, one carpet colour for another.
- **Grass to dirt.** Spare grass blocks, podzol, mycelium and dirt paths become the dirt a build needs. This is always free: no crafter hut, no waste, and it works even with the builder's swap and craft switches off.
- **Saw logs into planks.** Planks that run short are sawn from spare logs, of any type when swapping is on (spruce logs for oak planks or oak stairs). The Needed list counts spare logs as the planks they would make.
- **Craft from spare materials** using the server's normal crafting recipes, so modded recipes work too. Planks, wool and carpets of any type can stand in for each other inside recipes, e.g. spruce planks for oak stairs or white wool for a red carpet.
- **One-step sub-crafts.** A missing ingredient can be crafted first, e.g. an iron ingot into nuggets or planks into sticks. MineColonies racks are made from planks and iron, and the build tool they need is never used up.
- **Domum Ornamentum blocks.** Shingles, framed blocks, pillars and the like are cut as the Architect's Cutter would, from the exact texture blocks the build asks for.

Conversions wait a few seconds first, so you can see in the request window what is being converted. If the materials are gone by then, the request goes back to the rest of the colony.

### Tied to your colony's progress

Each kind of material needs the crafter who knows it:

| Material | Needs |
|---|---|
| Planks, logs, sticks | Sawmill |
| Cobblestone and other stone | Stonemason |
| Wool and carpets | Dyer |
| Ingots and nuggets | Blacksmith |
| Architect's Cutter blocks | Sawmill or Stonemason |

Converting also wastes some material, less as that crafter's hut is upgraded. By default it starts at 40% at level 1 and drops to none at level 5. Waste is rounded down, so small conversions early on can be free.

### Material Conversion tab

Every builder's hut gets a **Material Conversion** tab with:

- Switches to turn type swapping and crafting on or off for that builder. Hover them to see which crafter huts your colony has and how much each one wastes.
- **Needed:** the raw materials (planks, stone, ingots, wool...) the build would need if everything it is still missing were converted, with how much of each the builder has spare. Shown in red when it is short.
- **Spare:** what the builder holds beyond what the build needs.
- **Recent conversions.**

### Citizens escape hazards

A citizen that keeps getting hurt by cactus, fire, a campfire, a berry bush, a magma block or lava walks to the nearest safe spot nearby. It stands up from a chair or wakes up first if it needs to. If walking does not get it out within a few seconds, it is moved there directly. Once it is out, its flames are put out.

### Stuck citizens get unstuck

A citizen stuck inside a block (often its own workstation, after MineColonies teleports it to work) is moved out to the nearest safe spot after about 2 seconds. A citizen that keeps trying to walk somewhere without moving at all is moved a few steps along its own path (or to a safe spot near itself) after 30 seconds, long enough that citizens waiting at a door or behind someone are left alone. Its job then carries on from there. Citizens being walked out of a hazard are left to that. No more Recalling builders by hand.

### Bowls and bottles go back where they are used

Citizens keep the bowl after eating, huts collect them, and couriers rarely pick them up, so restaurants run out while researchers and doctors carry stacks of them. Every 10 seconds, empty bowls and glass bottles are taken from citizens and huts that do not use them:

- **Bowls** go to restaurants and kitchens (up to 64 each), then to the warehouse.
- **Glass bottles** go to the warehouse, which hands them to the alchemist and beekeeper when they ask.
- **Buckets** go back to the cowhand (up to 16 each), then to the warehouse. Chefs cooking with milk otherwise keep them for good.

They move instantly rather than by courier. Only what fits is moved, so nothing is ever lost. Couriers keep what they are carrying, and a building that has asked for bowls or bottles keeps them, along with its workers, until its worker has picked up the delivery and for 5 minutes after, so deliveries are not taken back before they are used.

### Restaurants keep their food

MineColonies only keeps one stack per restaurant level of each menu item, so couriers haul everything else to the warehouse, including food you stocked yourself, while citizens starve. Restaurants now keep any food, up to 4 stacks per level on top of the menu, so a full restaurant can still be emptied.

### Starving citizens eat what they carry

A citizen whose saturation drops to 2 (out of 20) eats the food it is carrying, and a waiter or cook with empty pockets eats from its own restaurant. This fixes waiters starving with a pocket full of meals and the restaurant stopping for everyone. It only steps in well below the point where citizens normally go to eat, so restaurant trips stay the usual way of eating.

### Couriers ride horses

Couriers borrow the colony's cavalry horses (the ones your Stablemaster trains) and ride them on long trips, about 75% faster than walking. A courier's horse follows it around, waits outside while it goes indoors, and the courier gets back on for the next long trip. Short trips are still walked.

Cavalry guards come first: a horse is only lent out while there are more free horses than guards without one, and a courier gives its horse back when a guard needs it. Horses that are given back walk home to their stable.

## Requirements

- Minecraft 1.21.1 with NeoForge 21.1
- MineColonies 1.1.1368 or newer within 1.1.x (brings Structurize, BlockUI and Domum Ornamentum with it)

## Installation

Put `coloniesplus-0.2.0.jar` in your `mods` folder.

On a server, install it on **both the server and every player's game**. It adds a tab to the builder hut, so a player without the mod (or a server without it) will not match and may crash or desync when opening a builder hut. Removing the mod from a world that has used it may cause errors loading that colony.

## Configuration

Open **Mods → coloniesPlus → Config** in game. This works with NeoForge's own Mods screen and with mod list mods such as Catalogue and Configured. There are two pages:

**Features** (`config/coloniesplus-common.toml`) turns each feature on or off for the whole game. You can change it from the main menu or while playing; changes apply straight away. On a dedicated server, the server's copy of this file is the one that counts.

| Setting | Default | What it does |
|---|---|---|
| `materialConversion` | `true` | Builders convert spare materials. |
| `materialSubstitution` | `true` | Swapping plank, stone, wool and carpet types. |
| `recipeConversion` | `true` | Crafting and Architect's Cutter conversions. |
| `logsToPlanks` | `true` | Sawing spare logs into planks. |
| `requireCrafterHuts` | `true` | Each kind of material needs its crafter hut. |
| `escapeHazards` | `true` | Citizens walk out of hazards. |
| `unstickCitizens` | `true` | Stuck citizens are moved to a safe spot. |
| `returnContainers` | `true` | Empty bowls, bottles and buckets are returned. |
| `keepRestaurantFood` | `true` | Restaurants keep food instead of couriers hauling it away. |
| `hungryWorkersEat` | `true` | Starving citizens eat the food they carry. |
| `courierHorses` | `true` | Couriers borrow cavalry horses. |

> **Upgrading from 0.1.0:** settings were renamed and split into these two files, so any changes you made in the old `coloniesplus-server.toml` are back to their defaults. Set them again here.

**World Settings** (`serverconfig/coloniesplus-server.toml` in each world) tunes the features for that world. NeoForge only allows editing it in game while playing single player (not opened to LAN); on a server, edit the file.

| Setting | Default | What it does |
|---|---|---|
| `conversion.intervalTicks` | `20` | How often builder requests are checked. |
| `conversion.conversionDelayTicks` | `60` | How long a conversion shows as in progress before the materials are swapped. |
| `conversion.wastePercentByLevel` | `[40, 30, 20, 10, 0]` | Percent of material wasted, by crafter hut level. Set all to 0 to turn waste off. |
| `citizens.hazardEscapeTicks` | `20` | How long a citizen keeps getting hurt before it moves. Hazards hurt every 10 ticks. |
| `citizens.stuckTicks` | `600` | How long a citizen trying to walk can stay in place before it counts as stuck. |
| `citizens.eatBelowSaturation` | `2` | Saturation (0–20) at which a citizen eats the food it carries. |
| `restaurant.foodStacksPerLevel` | `4` | Stacks of food a restaurant keeps per level, on top of its menu. |
| `containers.returnIntervalTicks` | `200` | How often bowls and bottles are returned. |
| `couriers.mountDistance` | `24` | The shortest trip, in blocks, a courier rides for. |
| `couriers.horseSpeed` | `1.75` | How fast a courier rides, as a multiple of its walking speed. |

Builders can also turn swapping and crafting off for themselves in their hut.

## Building from source

The repository does not include the Gradle wrapper yet, so use a local Gradle 8.8 install (or open the project in IntelliJ IDEA):

```sh
gradle build
```

The jar is written to `build/libs/`.

### Automated tests

```sh
gradle runGameTestServer
```

This starts a headless server with MineColonies, which also checks that the mod loads cleanly on a dedicated server. It then runs in-game tests for material conversion (grass to dirt, logs to planks, waste and crafter huts), where stuck citizens are moved to, returning bowls and buckets, and which food restaurants keep. The build fails if any test fails. The tests live in `src/gametest` and are not included in the mod jar.

## License

[MIT](LICENSE)
