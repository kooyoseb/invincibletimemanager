# InvincibleTimeManager

Minecraft 1.19.4 Fabric mod that creates and manages a generated datapack for the `minecraft:bypasses_cooldown` damage type tag.

## Commands

```mcfunction
/InvincibleTimeManager
/InvincibleTimeManager Version
/InvincibleTimeManager help
/InvincibleTimeManager reset
/InvincibleTimeManager add <damage_type>
/InvincibleTimeManager delete <damage_type>
/InvincibleTimeManager list
/InvincibleTimeManager Reload
```

The lowercase alias `/invincibletimemanager` is also registered.

## Generated Files

World datapack:

```text
<world>/datapacks/invincibletimemanager/
  pack.mcmeta
  data/minecraft/tags/damage_type/bypasses_cooldown.json
```

Damage type cache:

```text
.minecraft/moddata/invincibletimemanager/damage_types.json
```
