# coloniesPlus

An add-on for [MineColonies](https://www.curseforge.com/minecraft/mc-mods/minecolonies) that stops builders from getting stuck on materials they could make themselves, and stops citizens from standing in cactus and fire until they die.

> **Early test build (0.1.0).** Try it in a test world or back up your world first.

## Features

### Builders convert spare materials

When a builder is stuck waiting for a block that nobody else in the colony can supply, it can make that block from materials in its hut, racks or inventory that the current build does not need.

- **Swap material types.** Spruce planks for oak planks, one stone for another, white wool for red wool, one carpet colour for another.
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

## Requirements

- Minecraft 1.21.1 with NeoForge 21.1
- MineColonies 1.1.1368 or newer within 1.1.x (brings Structurize, BlockUI and Domum Ornamentum with it)

## Installation

Put `coloniesplus-0.1.0.jar` in your `mods` folder.

On a server, install it on **both the server and every player's game**. It adds a tab to the builder hut, so a player without the mod (or a server without it) will not match and may crash or desync when opening a builder hut. Removing the mod from a world that has used it may cause errors loading that colony.

## Configuration

Settings are per world, in `serverconfig/coloniesplus-server.toml` inside the world folder.

| Setting | Default | What it does |
|---|---|---|
| `enabled` | `true` | Turns material conversion on or off. |
| `intervalTicks` | `20` | How often builder requests are checked. |
| `conversionDelayTicks` | `60` | How long a conversion shows as in progress before the materials are swapped. |
| `allowMaterialSubstitution` | `true` | Allows swapping plank, stone, wool and carpet types. |
| `allowRecipeConversion` | `true` | Allows crafting and Architect's Cutter conversions. |
| `requireCrafterHuts` | `true` | Whether each kind of material needs its crafter hut. |
| `wastePercentByLevel` | `[40, 30, 20, 10, 0]` | Percent of material wasted, by crafter hut level. Set all to 0 to turn waste off. |
| `escapeHazards` | `true` | Whether citizens walk out of hazards. |
| `hazardEscapeTicks` | `20` | How long a citizen keeps getting hurt before it moves. Hazards hurt every 10 ticks. |

Builders can also turn swapping and crafting off for themselves in their hut.

## Building from source

The repository does not include the Gradle wrapper yet, so use a local Gradle 8.8 install (or open the project in IntelliJ IDEA):

```sh
gradle build
```

The jar is written to `build/libs/`.

## License

[MIT](LICENSE)
