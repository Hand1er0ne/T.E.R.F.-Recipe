# TERF Recipes

Fabric mod (Minecraft 26.1.2) that shows the machine recipes, custom items, fluids and
multiblock structures of the
[TERF](https://www.planetminecraft.com/data-pack/troty-energy-research-facility-infinity-update/)
datapack in **JEI**.

No recipe is hard-coded: the mod **replays `data/terf/function/startup.mcfunction`**
(only the `data modify/remove storage` commands), rebuilds the `terf:constants` storage in
memory and reads it exactly like the datapack's machines do. When the datapack adds a recipe or
even a whole new machine, it shows up on its own.

## Where the mod looks for the datapack

In this order (the first source found wins):

1. **Singleplayer**: the datapacks enabled in the world (read through the integrated server).
2. **`config/terf-recipes/`**: a `startup.mcfunction`, or the datapack zip / folder.
3. **Server resource pack**: when the server sends the full TERF zip (assets + data) as its
   resource pack, the mod reads it from `.minecraft/downloads`.
4. **GitHub copy**: downloaded from the datapack repository (see below).
5. **Copy bundled in the mod**: copied from `TERF_datapack/` at every build.

> A server never sends its `.mcfunction` files to clients: in multiplayer, sources 2 to 5 are
> used. To match a modified server exactly, put its datapack in `config/terf-recipes/` and run
> `/terfrecipes reload`.

## Updating from GitHub

When the game starts (in the background) and with `/terfrecipes update`, the mod compares the
latest commit of https://github.com/jona23EE/TERF_datapack (`main` branch) with its local copy
in `config/terf-recipes/github/`. When it changed, the repository is downloaded and only its
`data/` folder is kept. This copy replaces the bundled one; the world / server datapack (and
files put in `config/terf-recipes/`) still win. Settings: `config/terf-recipes/github.json`
(`enabled`, `repo`, `branch`, `path`, `checkOnStartup`).

## What is shown

- One JEI tab per machine: Fabricator (3x3 grid), Crusher, Rolling Mill, Extrusion / Shearing /
  Electric Press, EBF, Hadron Collider, OpenCore, Electrolyzer, Wet Mill, Large Fluid
  Solidifier...
- Custom items (plates, screws, rotors, tools...) built with the same components as
  `terf:require/custom_item_summon`, added to the JEI item list (R / U work on them).
- Fluids as a TERF Syringe filled with the fluid (colour, formula, temperature; amount in the
  tooltip).
- Random outputs (e.g. `random_ore`) with their chance.
- Recipe parameters (time, amounts, ring length...) and OpenCore steps.
- A charged copy of rechargeable items the datapack gives empty (Electron Bomb), in the item
  list only.

Custom item textures come from the TERF resource pack: it has to be loaded (in
`resourcepacks/` or sent by the server).

## Machine structures ("TERF Multiblocks" tab)

The datapack has no structure files: the `mb_setup_functions` table (in `startup.mcfunction`)
gives the block the Multiblock Core goes in and the setup function of each machine, and the
shape only exists as `if block ^x ^y ^z <block>` conditions in the setup / tick / checks
functions. The mod follows these functions and rebuilds the structure.

One page per machine (sorted by name):

- **left**: every block needed with its amount (U on a block = machines using it);
- **right**: a **3D view** (drag to rotate, scroll to zoom, ◀ ▶ to hide / show the top layers).
  Small machines also have a **2D** top view, one layer at a time. The red corners mark the
  core block, the green arrow the side to stand on when placing the core (it turns to face
  the player who places it);
- **Show in world**: see below.

This is an automatic reading of the datapack's code. Machines checked with loops or dynamic
functions are corrected in `machines.json` (see "Customising").

## Finding a machine

Every machine has a `<Machine> - Structure` item in the JEI item list, drawn with the
machine's icon. Type its name (or `structure` for all of them) in the JEI search bar:

- **R / left click**: how to build it (structure page);
- **U / right click**: what it makes (recipe tab).

The same item is shown next to each recipe tab, and clicking the machine name on a structure
page opens its recipes. Backspace goes back to the previous JEI page.

## Hologram in the world

On a structure page, **Show in world** shows the structure as ghost blocks on the block you are
looking at (where the Multiblock Core goes), turned so that the core faces you. Blocks placed
correctly disappear, wrong ones turn red, and the action bar counts what is missing.
Keys (rebindable, "TERF Recipes" category): **R** rotate, **J** layer by layer, **G** move here,
**H** remove (or `/terfrecipes hologram clear`). Client side only: works on any server.

## Fabricator "+" button

Open the Fabricator's Crafter, find the recipe in JEI and click "+": the grid is filled from
your inventory (shift-click: as many as possible). It only uses normal inventory clicks, so it
works on servers without JEI.

## Hiding recipes

`config/terf-recipes/hidden.txt` (created on first launch, with examples), then
`/terfrecipes reload`:

```
machine:dem                      # a whole machine tab (and its structure)
output:terf:gravity_gun          # recipes producing this item
input:minecraft:netherite_ingot  # recipes using this ingredient
recipe:fabricator/*gold*         # by id (shown with F3+H on the output)
item:terf:command_block_staff    # removes a custom item from the JEI list
```

Defaults are set in `machines.json`: `"hideRecipes": true` hides a machine's recipe tab
(Fission Fuel Loader, DEM, Assembler), `"hiddenRecipes": [...]` hides single recipes (OpenCore
with the Repeating Command Block), `"structure": "hidden"` hides a structure page (Assembler).

Other display tweaks: Red Glazed Terracotta (the "power flowing" state of a wire corner) is
shown as a High Voltage Conductor Slab; JEI's Grindstone pages are hidden for rechargeable
items; water / lava are real source blocks in the 3D view.

## Commands (client side)

- `/terfrecipes`: number of recipes / items and the source in use
- `/terfrecipes reload`: reads the datapack again and updates JEI without restarting
  (a *new* machine needs a rejoin to get its tab)
- `/terfrecipes update`: checks GitHub and reloads when a newer datapack is available
- `/terfrecipes hologram clear`: removes the hologram

## Customising

`src/main/resources/terf-recipes/machines.json` only describes presentation. Copy it to
`config/terf-recipes/machines.json` to change it without rebuilding (a machine written there
replaces its whole entry). Main fields per machine:

- `name`, `icon` (without an icon, the block the core goes in is used), parameter labels
  (`params`), `note`;
- `hideRecipes`, `hiddenRecipes`, `structure` (`auto` | `layers` | `list` | `hidden`);
- structure fixes: `structureAdd` (`[{"pos":[x,y,z],"block":"id[states]"}]`),
  `structureRemove` (`[[x,y,z]]`), `structureRemoveBlocks` (`["slime_block"]`),
  `structureIgnore` (functions not to follow), `raycastSteps` (self-repeating checks shown
  n steps away), `structureNotes`, `blockTips` (extra tooltip per block).
  Coordinates are relative to the core: x = left, y = up, z = front.

## Code

- `com.terfrecipes.data`: SNBT parser, NBT paths, storage emulator, recipe and structure
  extraction. No Minecraft dependency (testable on its own).
- `com.terfrecipes.client`: data sources, GitHub updater, ItemStack building, commands.
- `com.terfrecipes.client.jei`: JEI plugin, categories, transfer handler.
- `com.terfrecipes.client.render`: 3D view and in-world hologram.

## License

TERF Recipes is **All Rights Reserved** © 2026 Hand1er0ne (see `LICENSE`): free to use and to
include in modpacks through its Modrinth page, but no re-upload, redistribution or reuse of the
code without permission. The TERF datapack files bundled in the jar are © jona23, All Rights
Reserved, included with permission (see `NOTICE`).
