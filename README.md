# StevePaperAI

PaperMC plugin for a mortal survival-mode AI helper NPC.

## Requirements

- Paper 1.20.1-compatible server
- Java 17+
- Citizens plugin installed on the server
- DeepSeek-compatible API key

## Current milestone: 0.5.0 survival helper

Bob now has a persistent survival inventory and more useful survival skills:

- Citizens NPC body, mortal, 20 health, not protected, not invulnerable.
- Follow owner and protect owner modes.
- Persistent `agents.yml` storage for NPC id, owner, location, inventory, equipped item, follow/protect state.
- Cleanup/adopt old Citizens NPCs.
- Collect item entities into Bob's inventory.
- Equip the best available tool from Bob's inventory.
- Break target blocks and nearby blocks, storing drops in Bob's inventory.
- Place blocks from Bob's inventory.
- Chop connected tree logs.
- Mine connected ore veins.
- Craft a small starter set of items: planks, sticks, crafting table, wooden tools, stone tools, torches.
- Open nearby chest/furnace/crafting table for the owner.
- Attack nearby hostile mobs and protect the owner.
- Flee back toward owner.

Bob still does not use creative/admin powers:

- no console commands
- no setblock/fill
- no free teleport for tasks
- no spawning items

## Build

```bat
gradlew.bat build
```

The plugin jar will be in:

```text
build/libs/StevePaperAI-0.5.3.jar
```

Put both jars into the Paper server `plugins` folder:

```text
Citizens.jar
StevePaperAI-0.5.3.jar
```

Then set your API key in:

```text
plugins/StevePaperAI/config.yml
```

If updating from old sandbox builds, delete the old config once so the survival config regenerates, then paste your API key back in.

## Commands

```text
/steve spawn Bob
/steve follow Bob
/steve protect Bob
/steve unprotect Bob
/steve sit Bob
/steve standup Bob
/steve come Bob
/steve attack Bob
/steve collect Bob
/steve ask Bob follow me
/steve ask Bob protect me
/steve ask Bob stop protecting
/steve ask Bob collect nearby items
/steve ask Bob chop a nearby tree
/steve ask Bob mine nearby coal ore
/steve ask Bob craft oak planks
/steve ask Bob craft sticks
/steve ask Bob place oak planks on the block I am looking at
/steve ask Bob open the nearest chest
/steve inventory Bob
/steve cleanup Bob
/steve adopt Bob [npcId]
/steve status
/steve stop Bob
/steve remove Bob
```

## Known limits

This is still a survival baseline, not a full player simulation. Bob has a plugin-managed inventory, not a real logged-in player inventory. Chest/furnace/crafting-table support is basic: Bob can move to/open them for the owner, but deep deposit/withdraw/smelting automation is the next step.

## Auto pickup

Bob automatically picks up dropped item entities near him when gent.auto-pickup is true.
