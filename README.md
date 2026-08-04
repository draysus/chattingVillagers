# Chatting Villagers

A Fabric mod for Minecraft 1.21.1 that gives villagers a voice: they speak context-aware lines in chat and hold short conversations with each other, matched to time of day, weather, profession, biome and situation. Runs **entirely on the server** — other players don't need to install anything.

---

## Language

**English and German are included. English is the default.** To switch, edit `config/chattingvillagers.json`:

```json
"language": "de_de"
- or:
"language": "en_us"
```

Then run `/chattingvillagers reload` or restart. The language folder is created automatically.

This is a **server setting**, not a per-player one — everyone sees the same language, because finished text is sent to clients rather than translation keys.

---

## Commands

| Command | Effect | Permission |
| --- | --- | --- |
| `/chattingvillagers on` \| `off` \| `toggle` | Village chat on/off for yourself | everyone |
| `/chattingvillagers status` | Show your own setting | everyone |
| `/chattingvillagers frequency quiet` \| `normal` \| `busy` | Switch frequency preset | OP (level 2) |
| `/chattingvillagers bubbles on` \| `off` | Speech bubbles on/off globally | OP (level 2) |
| `/chattingvillagers chat on` \| `off` | Chat output on/off (off = bubbles only) | OP (level 2) |
| `/chattingvillagers reload` | Reload config and text catalogue | OP (level 2) |
| `/chattingvillagers debug` | Show the nearest villager's context | OP (level 2) |

---

## Editing and extending the lines

The full text catalogue is written to `config/chattingvillagers/dialogue/<language>/` on first start and is **never overwritten** by updates. Edit lines, delete them, add your own, then `/chattingvillagers reload` — no recompiling.

An entry looks like this:

```json
{
  "id": "farmer_morning",
  "type": "solo",
  "conditions": {
    "profession": ["farmer"],
    "timeOfDay": ["morning"]
  },
  "weight": 1,
  "text": "Out in the field early - that's how I like it."
}
```

Empty or missing conditions mean "always fits". The weight controls frequency: `1` for normal lines, `0.2` or `0.1` for rare ones. Any additional `.json` file in a language folder is loaded automatically as a new pool of solo lines.

`/chattingvillagers debug` is useful here — it shows the nearest villager's context and how many lines currently match.

---

## Features

- **Context-aware lines** — time of day, weather, all 13 professions plus jobless/nitwit/wandering trader, 34 biome categories, dimension, indoor/outdoor, underground/surface, nearby portals, and state (low health, hurt, trading).
- **Conversations** — two nearby villagers exchange a short dialogue, addressing each other by name.
- **Right-click reactions** — jobless villagers and nitwits answer briefly. Vanilla behaviour (head shake, trading menu) is untouched.
- **Speech bubbles** — the line optionally appears above the villager's head, visible to all players, no client mod needed.
- **Anti-spam** — radius limit, per-villager and per-pair cooldowns, per-player rate limit, no immediate repeats, quieter nights, three frequency presets.
- **Per-player opt-out** — `/chattingvillagers off` mutes village chat for yourself only; persists across restarts.
- **1804 lines per language** out of the box: 767 solo lines, 238 conversations (616 lines), 421 reactions.
- **Villager Names compatible** — works out of the box with the Villager Names mod: chat lines, conversations, reactions and speech bubbles all use the assigned name automatically.

---

## Requirements and installation

- Minecraft **1.21.1**, Fabric Loader **0.15.11+**, **Fabric API**, Java **21**
- Put `chattingvillagers-<version>.jar` and Fabric API in your `mods` folder. The same jar works in singleplayer, on a dedicated server, and as an optional client install.
- Other players do **not** need the mod.

Optional, fully compatible: [**Villager Names**](https://www.curseforge.com/minecraft/mc-mods/villager-names) by Serilum gives villagers custom names — Chatting Villagers picks these up everywhere automatically. Without it, it falls back to the profession name.

Configuration lives in `config/chattingvillagers.json` (colours, radius, frequency, cooldowns, bubble lifetime, and more), applied live with `/chattingvillagers reload`.

---

## Building from source

```bash
git clone https://github.com/draysus/chattingvillagers.git
cd chattingvillagers
./gradlew build
```

Requires Java 21. The jar appears in `build/libs/`.

## Contributing

Bug reports, suggestions and **translation pull requests** are welcome. For a new language, copy a language folder and translate the texts — keep the same ids, conditions, weights and line counts so the pools stay aligned. No code changes needed.

## License

MIT — use it, change it, put it in your modpack. See [LICENSE](LICENSE).
