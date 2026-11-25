# Haunt

`/haunt <player>` - haunt someone with fake players and jumpscares. **Only the haunted player sees or hears any of it.**

## Commands
- `/haunt <player> [duration] [intensity 1-3]` - e.g. `/haunt Steve 5m 3` (duration: `90`, `90s`, `5m`, `1h`)
- `/haunt scare <player|all> [type] [intensity 1-3]` - scare someone right now, **no haunting needed**. `type` is any scare below, `random` (default) or `cave`.
  e.g. `/haunt scare Steve jumpscare`, `/haunt scare Steve cave 3`, `/haunt scare all random`
- `/haunt stop <player|all>`, `/haunt list`, `/haunt reload`
- `/haunt optout` - a player turns haunting off for themselves (toggle)

Permissions: `haunt.use` (ops), `haunt.bypass` (cannot be haunted), `haunt.optout` (everyone).

## What it does
- **Fake players** are Mannequin entities (1.21.9+), with the haunted player's own skin, another online player's, or one from `names`.
  On older servers they fall back to posed armor stands with a player head.
- **Stalker**: stands far away staring; vanishes if you look at it. **Behind**: appears right behind you; turn around and it's in your face.
  **Rush**: sprints at you and screams. **Crowd**: a ring of them closes in. **Whisper**: fake chat/join messages. **Sound**: footsteps, doors, chests.
- Warden heartbeat that speeds up as they get close, plus night and rain for them only.
- Hitting a ghost makes it vanish. Everything is removed when the haunt ends, the player dies, leaves, or you `/haunt stop`.

## Build
`mvn -B package` (Java 21) or push to GitHub and download the **Haunt** artifact. Needs only Paper. Works on Folia.

## New in 1.1 (much scarier)
- **Angel**: a glowing figure that only moves while you are *not* looking at it. Look away for a second and it is closer.
- **Lights out**: nearby torches and lanterns go dark (for you only), the world goes silent, a heartbeat starts, then it is behind you.
- **Watcher**: a glowing figure far away that you can see through walls. It never vanishes when watched and slowly creeps closer.
- **Silence**: every sound cuts out, you hear a breath right behind you, then it appears.
- Messages and subtitles use the victim's own name (`{player}`), fake deaths and advancements, drifting ash, breathing behind you,
  rare music disc 11, random screams, and at intensity 3 a "fake-out" second scare right after the first.
- Intensity 3 is now a proper nightmare (a scare every 3-9 seconds). Intensity 1 stays mild.

Safety: players can still `/haunt optout`, `haunt.bypass` still protects people, nothing deals damage, and the screen effects are single
slow pulses (no strobing), with a jumpscare cooldown. Only ever haunt people who are in on it.

## New in 1.2
- **`/haunt scare` works on anyone.** It no longer needs `/haunt <player>` first. If they are not being haunted it runs a quiet one-shot scare
  (no night, no rain, no random scares afterwards) and ends by itself. If they are being haunted it uses that haunting. `all` scares everyone online.
  `haunt.bypass` and `/haunt optout` are still respected.
- **Cave scares.** While a haunted player is underground (no sky above, 8+ blocks below the surface) about half of their scares become cave scares,
  and the ambience turns into drips, rock shifting and far-off groans, with dust trickling from the ceiling:
  - **cavein**: the tunnel behind them collapses. Fake rubble (only they see it, nothing real changes) blocks the way back for a few seconds. (intensity 2+)
  - **tapping**: digging through the rock that gets closer and closer, then the wall breaks and it is behind them.
  - **eyes**: pairs of red eyes far down a tunnel that blink and creep closer.
  - **peeker**: a figure at the end of the tunnel that backs away into the dark when looked at.
  - **rumble**: dust and rocks fall from the ceiling, with crashes somewhere in the dark.
  Tune it under `cave:` in `config.yml`. `/haunt scare <player> cave` or any of the names above works anywhere.

## New in 1.3 - Nightmare
The big change: **a haunting now builds**. It used to fire random scares at a flat rate. Now it starts subtle (things you can talk yourself
out of), turns aggressive, and ends with a scripted finale. Tune it under `nightmare:` in `config.yml`.

- **Dread director**: subtle scares dominate early, violent ones late, and the gaps between scares shrink as it goes. A low heartbeat creeps in
  once dread builds and never fully leaves.
- **Finale** (long hauntings, intensity 2+): blackout, every sound cuts, an accelerating heartbeat, *your own double* appears far away,
  something breathes behind you, `<yourname> look behind you` appears in chat, then everything arrives at once.
  Trigger it any time with `/haunt scare <player> finale`.
- **Mimic**: a double with your skin and name copies every step you take from far away. Stop moving and it turns around and walks to you;
  look at it and it freezes. **Treeline**: a silent figure at the edge of the render distance that is gone the moment you really look.
  **Knock**: knocking that gets louder, then a door opens behind you. **Hiss**: a creeper hisses behind you and nothing is there.
  **Wrong**: warped villager noises, a growl in the dark, someone screaming far away. **Eyes** now work outside caves too.
- **Personal**: messages and jumpscare subtitles can use `{x} {y} {z} {item} {health} {world}` (the victim's own in-game info, shown only to them).
- **Relentless rush**: at intensity 3 the rusher comes back up to twice after it vanishes.

### Safety (unchanged on purpose)
Opt-out (`/haunt optout`), `haunt.bypass`, no damage, only slow darkness pulses (no strobing or rapid flashes), jumpscare cooldown, and ghosts
are visible only to the haunted player. Haunt people who are in on it. Haunt never fakes kicks, bans, crashes, IP addresses or anything about the
real world, and it only uses a player's own in-game data.
