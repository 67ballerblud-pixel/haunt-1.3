package me.fix.haunt;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One haunting of one player. Everything here runs on that player's own scheduler (so it is Folia safe) and only
 * the haunted player ever sees or hears any of it.
 */
final class Haunt {

    enum Type {
        STALKER, BEHIND, RUSH, CROWD, WHISPER, SOUND, JUMPSCARE, ANGEL, LIGHTSOUT, WATCHER, SILENCE,
        // 1.3 nightmare scares
        TREELINE, MIMIC, KNOCK, HISS, WRONG, FINALE,
        // cave scares (use the tunnels, rock and ceiling around the player)
        CAVEIN, TAPPING, EYES, PEEKER, RUMBLE
    }

    /** A straight, unobstructed line at head height from the player (used to find tunnels in a cave). */
    private record Line(Vector dir, double len) {
    }

    private static final class Ghost {
        final Entity entity;
        final Type type;
        Location pos;
        int age;
        final int ttl;
        boolean closing;
        boolean turned;
        int hideAt = Integer.MAX_VALUE;
        int watch;
        int gen;
        Location lastP;

        Ghost(Entity entity, Type type, Location pos, int ttl) {
            this.entity = entity;
            this.type = type;
            this.pos = pos;
            this.ttl = ttl;
        }
    }

    private record Later(int due, Runnable action) {
    }

    private final HauntPlugin plugin;
    private final Ghosts ghosts;
    final UUID targetId;
    final String targetName;
    final int intensity;
    private final long endsAt;
    private final List<Ghost> live = new ArrayList<>();
    private final List<Later> later = new ArrayList<>();
    private ScheduledTask task;
    private int ticks;
    private long nextScare;
    private long nextAmbient;
    private long lastJumpscare;
    private int lastBeat;
    private boolean ended;
    private final List<Location> dimmed = new ArrayList<>();
    private int silentUntil;
    private int lastDisc = -100000;
    private long lastAfter;
    /** Fake rubble sent only to the player (cave-in). */
    private final List<Location> faked = new ArrayList<>();
    /**
     * A quiet session is a one-shot scare started by /haunt scare on someone who is not being haunted: no night,
     * no rain, no random scares or ambience. It just runs the scare and ends itself when everything is gone.
     */
    private final boolean quiet;
    private final long startMs = System.currentTimeMillis();
    private boolean finaleDone;

    Haunt(HauntPlugin plugin, Ghosts ghosts, Player target, int seconds, int intensity, boolean quiet) {
        this.plugin = plugin;
        this.ghosts = ghosts;
        this.targetId = target.getUniqueId();
        this.targetName = target.getName();
        this.intensity = Math.max(1, Math.min(3, intensity));
        this.endsAt = System.currentTimeMillis() + seconds * 1000L;
        this.quiet = quiet;
    }

    boolean quiet() {
        return quiet;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Call on the target's thread. */
    void start(Player p) {
        if (!quiet && cfg().getBoolean("ambience.night", true)) {
            p.setPlayerTime(18000L, false);
            p.setPlayerWeather(WeatherType.DOWNFALL);
        }
        long now = System.currentTimeMillis();
        nextScare = quiet ? Long.MAX_VALUE : now + 4000L;
        nextAmbient = quiet ? Long.MAX_VALUE : now + 6000L;
        task = p.getScheduler().runAtFixedRate(plugin, t -> {
            try {
                tick(p);
            } catch (Throwable ex) {
                plugin.getLogger().warning("Haunt tick failed for " + targetName + ": " + ex);
                plugin.stopHaunt(targetId);
            }
        }, () -> end(null), 1L, 2L);
    }

    /** Call on the target's thread (p may be null if they are gone). */
    void end(Player p) {
        if (ended) return;
        ended = true;
        if (task != null) task.cancel();
        for (Ghost g : live) ghosts.remove(g.entity);
        live.clear();
        later.clear();
        if (p != null && p.isOnline()) {
            restoreLights(p);
            restoreFaked(p);
            if (!quiet) {
                p.resetPlayerTime();
                p.resetPlayerWeather();
            }
        }
        faked.clear();
    }

    boolean ended() {
        return ended;
    }

    /** Removes the fake players that are currently out (used after a world change). */
    void clearGhosts() {
        for (Ghost g : live) ghosts.remove(g.entity);
        live.clear();
    }

    int ghostCount() {
        return live.size();
    }

    private void tick(Player p) {
        if (ended) return;
        if (!p.isOnline() || p.isDead()) {
            plugin.stopHaunt(targetId);
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= endsAt) {
            plugin.stopHaunt(targetId);
            return;
        }
        ticks += 2;

        Iterator<Later> li = later.iterator();
        List<Runnable> due = new ArrayList<>();
        while (li.hasNext()) {
            Later l = li.next();
            if (l.due() <= ticks) {
                due.add(l.action());
                li.remove();
            }
        }
        for (Runnable r : due) r.run();

        updateGhosts(p);
        heartbeat(p);
        atmosphere(p);
        dreadBeat(p);
        if (!quiet && !finaleDone && intensity >= 2 && cfg().getBoolean("nightmare.finale", true)
                && dread() >= 0.9 && endsAt - now > 25000L) {
            finaleDone = true;
            finale(p);
        }

        // a one-shot scare ends by itself once its ghosts and timed effects are done
        if (quiet && ticks > 20 && live.isEmpty() && later.isEmpty()) {
            plugin.stopHaunt(targetId);
            return;
        }

        if (now >= nextScare) {
            Type t = pick(p);
            if (t != null) scare(p, t);
            int[] range = interval();
            nextScare = now + (range[0] + ThreadLocalRandom.current().nextInt(Math.max(1, range[1] - range[0] + 1))) * 1000L;
        }
        if (cfg().getBoolean("ambience.sounds", true) && now >= nextAmbient) {
            ambient(p);
            nextAmbient = now + ambientGap();
        }
    }

    private void later(int inTicks, Runnable r) {
        later.add(new Later(ticks + inTicks, r));
    }

    private int[] interval() {
        List<Integer> l = cfg().getIntegerList("scares.interval." + intensity);
        int lo = 10;
        int hi = 25;
        if (l.size() >= 2) {
            lo = l.get(0);
            hi = Math.max(l.get(0), l.get(1));
        }
        if (!quiet && cfg().getBoolean("nightmare.escalate", true)) {
            // slow and unsettling at first, relentless near the end
            double f = 1.35 - 0.85 * dread();
            lo = (int) Math.max(2, Math.round(lo * f));
            hi = Math.max(lo, (int) Math.round(hi * f));
        }
        return new int[]{lo, hi};
    }

    /** 0 = the haunting just started, 1 = it is at its worst. Drives which scares are picked and how fast they come. */
    private double dread() {
        if (quiet) return 0.7;
        if (!cfg().getBoolean("nightmare.escalate", true)) return 0.5;
        long total = Math.max(1000L, endsAt - startMs);
        long ramp = Math.min(total, cfg().getLong("nightmare.ramp-seconds", 150) * 1000L);
        return Math.max(0, Math.min(1, (System.currentTimeMillis() - startMs) / (double) ramp));
    }

    // ------------------------------------------------------------------ choosing a scare

    /** Picks a scare. While the player is in a cave, a share of the scares are cave scares instead. */
    private Type pick(Player p) {
        if (caveActive(p) && ThreadLocalRandom.current().nextInt(100) < cfg().getInt("cave.chance", 55)) {
            Type c = pickCave();
            if (c != null) return c;
        }
        return pickNormal();
    }

    private Type pickCave() {
        Type[] types = {Type.CAVEIN, Type.TAPPING, Type.EYES, Type.PEEKER, Type.RUMBLE};
        String[] keys = {"cavein", "tapping", "eyes", "peeker", "rumble"};
        int[] defaults = {10, 18, 16, 16, 20};
        int[] minIntensity = {2, 1, 1, 1, 1};
        int total = 0;
        int[] w = new int[types.length];
        for (int i = 0; i < types.length; i++) {
            if (intensity < minIntensity[i]) continue;
            w[i] = Math.max(0, cfg().getInt("cave.weights." + keys[i], defaults[i]));
            total += w[i];
        }
        if (total <= 0) return null;
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (int i = 0; i < types.length; i++) {
            roll -= w[i];
            if (roll < 0) return types[i];
        }
        return null;
    }

    /** /haunt scare <player> random: any scare, cave scares included when they are in a cave. */
    void scareRandom(Player p) {
        Type t = pick(p);
        if (t == null) t = Type.JUMPSCARE;
        scare(p, t);
    }

    /** /haunt scare <player> cave: a random cave scare, whether or not they are really in a cave. */
    void scareCave(Player p) {
        Type t = pickCave();
        scare(p, t == null ? Type.RUMBLE : t);
    }

    private Type pickNormal() {
        Type[] types = {Type.STALKER, Type.BEHIND, Type.RUSH, Type.CROWD, Type.WHISPER, Type.SOUND, Type.ANGEL, Type.LIGHTSOUT,
                Type.WATCHER, Type.SILENCE, Type.TREELINE, Type.MIMIC, Type.KNOCK, Type.HISS, Type.WRONG, Type.EYES};
        String[] keys = {"stalker", "behind", "rush", "crowd", "whisper", "sound", "angel", "lightsout", "watcher", "silence",
                "treeline", "mimic", "knock", "hiss", "wrong", "eyes"};
        int[] defaults = {20, 22, 15, 8, 15, 20, 18, 10, 12, 10, 22, 12, 14, 10, 16, 12};
        int[] minIntensity = {1, 2, 2, 2, 1, 1, 2, 2, 2, 2, 1, 2, 1, 1, 1, 1};
        // subtle scares come first; the violent ones take over as dread builds
        boolean[] subtle = {true, false, false, false, true, true, false, false, true, false, true, false, true, false, true, true};
        double d = dread();
        int total = 0;
        int[] w = new int[types.length];
        for (int i = 0; i < types.length; i++) {
            if (intensity < minIntensity[i]) continue;
            int base = Math.max(0, cfg().getInt("scares.weights." + keys[i], defaults[i]));
            double factor = subtle[i] ? 1.5 - 1.1 * d : 0.35 + 1.15 * d;
            w[i] = base == 0 ? 0 : Math.max(1, (int) Math.round(base * factor));
            total += w[i];
        }
        if (total <= 0) return null;
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (int i = 0; i < types.length; i++) {
            roll -= w[i];
            if (roll < 0) return types[i];
        }
        return null;
    }

    void scare(Player p, Type t) {
        switch (t) {
            case STALKER -> stalker(p);
            case BEHIND -> behind(p);
            case RUSH -> rush(p);
            case CROWD -> crowd(p);
            case WHISPER -> whisper(p);
            case SOUND -> soundScare(p);
            case ANGEL -> angel(p);
            case LIGHTSOUT -> lightsOut(p);
            case WATCHER -> watcher(p);
            case SILENCE -> silence(p);
            case TREELINE -> treeline(p);
            case MIMIC -> mimic(p);
            case KNOCK -> knock(p);
            case HISS -> hiss(p);
            case WRONG -> wrong(p);
            case FINALE -> finale(p);
            case JUMPSCARE -> jumpscare(p);
            case CAVEIN -> caveIn(p);
            case TAPPING -> tapping(p);
            case EYES -> eyes(p);
            case PEEKER -> peeker(p);
            case RUMBLE -> rumble(p);
        }
    }

    // ------------------------------------------------------------------ cave awareness

    /** Underground in the overworld with no sky above them and well below the surface. */
    boolean inCave(Player p) {
        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return false;
        Location l = p.getLocation();
        int surface = w.getHighestBlockYAt(l);
        return l.getBlock().getLightFromSky() <= 2 && surface - l.getBlockY() >= cfg().getInt("cave.min-depth", 8);
    }

    private boolean caveActive(Player p) {
        return cfg().getBoolean("cave.enabled", true) && inCave(p);
    }

    /** Flat directions with a long free line of sight at head height, in random order. */
    private List<Line> openLines(Player p, double minLen, double maxLen) {
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24;
            Vector d = new Vector(Math.cos(a), 0, Math.sin(a));
            RayTraceResult r = w.rayTraceBlocks(eye, d, maxLen, FluidCollisionMode.NEVER, true);
            double len = r == null ? maxLen : r.getHitPosition().distance(eye.toVector());
            if (len >= minLen) out.add(new Line(d, len));
        }
        Collections.shuffle(out);
        return out;
    }

    private BlockData ceilingData(Player p) {
        Location l = p.getLocation();
        for (int dy = 2; dy <= 8; dy++) {
            Material m = l.getBlock().getRelative(0, dy, 0).getType();
            if (m.isSolid() && m.isBlock()) return m.createBlockData();
        }
        return Material.STONE.createBlockData();
    }

    private void restoreFaked(Player p) {
        for (Location l : faked) {
            try {
                p.sendBlockChange(l, l.getBlock().getBlockData());
            } catch (Throwable ignored) {
            }
        }
        faked.clear();
    }

    // ------------------------------------------------------------------ the cave scares

    /** A figure at the far end of a tunnel. When you look at it, it backs away into the dark. */
    private void peeker(Player p) {
        Location eye = p.getEyeLocation();
        for (Line ln : openLines(p, 11, 26)) {
            double d = Math.min(ln.len() - 1.5, 22);
            Location at = groundAt(p.getWorld(), eye.getX() + ln.dir().getX() * d, p.getLocation().getY(),
                    eye.getZ() + ln.dir().getZ() * d);
            if (at == null) continue;
            if (addGhost(p, Type.PEEKER, at, 400) != null) {
                p.playSound(p.getLocation(), "minecraft:ambient.cave", SoundCategory.MASTER, 0.8f, 0.5f);
                return;
            }
        }
        stalker(p);
    }

    /** Something is digging towards you through the rock. The taps get closer and then the wall breaks. */
    private void tapping(Player p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang = r.nextDouble() * Math.PI * 2;
        final double ax = Math.cos(ang);
        final double az = Math.sin(ang);
        final int taps = 9;
        for (int i = 0; i < taps; i++) {
            final double dist = 14 - i * 1.3;
            final int idx = i;
            later(i * 14 + r.nextInt(5), () -> {
                ThreadLocalRandom rr = ThreadLocalRandom.current();
                Location at = p.getLocation().add(ax * dist, rr.nextDouble(-1, 1.5), az * dist);
                String snd = idx == taps - 1 ? "minecraft:block.stone.break"
                        : (idx % 2 == 0 ? "minecraft:block.stone.hit" : "minecraft:block.deepslate.hit");
                p.playSound(at, snd, SoundCategory.MASTER, 0.5f + idx * 0.07f, 0.7f + rr.nextFloat() * 0.3f);
            });
        }
        later(taps * 14 + 20, () -> p.playSound(p.getLocation(), "minecraft:entity.warden.nearby_close", SoundCategory.MASTER, 0.8f, 0.7f));
        if (intensity >= 2) later(taps * 14 + 60, () -> behind(p));
    }

    /** Pairs of red eyes in the dark far down a tunnel. They blink and creep closer, then are gone. */
    private void eyes(Player p) {
        List<Line> lines = openLines(p, 9, 22);
        if (lines.isEmpty()) {
            whisper(p);
            return;
        }
        Line ln = lines.get(0);
        final Vector d = ln.dir().clone();
        final Location origin = p.getEyeLocation().clone();
        final double start = Math.min(ln.len() - 1, 18);
        final Vector perp = new Vector(-d.getZ(), 0, d.getX()).multiply(0.18);
        final Particle.DustOptions red = new Particle.DustOptions(Color.fromRGB(255, 30, 30), 0.8f);
        p.playSound(p.getLocation(), "minecraft:entity.warden.nearby_close", SoundCategory.MASTER, 0.5f, 0.6f);
        for (int i = 0; i < 22; i++) {
            final int s = i;
            later(i * 5, () -> {
                if (s % 7 == 6) return; // blink
                double dd = Math.max(3, start - s * 0.45);
                Location c = origin.clone().add(d.clone().multiply(dd));
                p.spawnParticle(Particle.DUST, c.clone().add(perp), 1, 0, 0, 0, 0, red);
                p.spawnParticle(Particle.DUST, c.clone().subtract(perp), 1, 0, 0, 0, 0, red);
            });
        }
    }

    /** Dust and small rocks fall from the ceiling with a deep rumble, then a distant crash. */
    private void rumble(Player p) {
        final BlockData rock = ceilingData(p);
        for (int i = 0; i < 6; i++) {
            final int s = i;
            later(i * 9, () -> {
                Location up = p.getEyeLocation().add(0, 1.2, 0);
                p.spawnParticle(Particle.FALLING_DUST, up, 14, 1.8, 0.2, 1.8, 0, rock);
                p.playSound(p.getLocation(), "minecraft:block.gravel.fall", SoundCategory.MASTER, 0.6f, 0.5f);
                if (s % 2 == 0) {
                    p.playSound(p.getLocation(), "minecraft:entity.generic.explode", SoundCategory.MASTER, 0.12f, 0.5f);
                }
            });
        }
        later(60, () -> {
            ThreadLocalRandom rr = ThreadLocalRandom.current();
            Location far = p.getLocation().add(rr.nextDouble(-12, 12), 0, rr.nextDouble(-12, 12));
            p.playSound(far, "minecraft:block.stone.break", SoundCategory.MASTER, 1.0f, 0.5f);
            p.playSound(far, "minecraft:block.gravel.break", SoundCategory.MASTER, 1.0f, 0.5f);
        });
        later(80, () -> p.playSound(p.getLocation(), "minecraft:block.pointed_dripstone.drip_water", SoundCategory.MASTER, 1.0f, 0.8f));
    }

    /**
     * The tunnel behind you collapses: rumble, dust, and fake rubble that blocks the way back for a few seconds.
     * The rubble is only sent to the player (nothing in the real world changes) and is restored afterwards.
     */
    private void caveIn(Player p) {
        Vector flat = p.getEyeLocation().getDirection().setY(0);
        if (flat.lengthSquared() < 0.01) flat = new Vector(0, 0, 1);
        flat.normalize();
        World w = p.getWorld();
        Location centre = null;
        for (double d : new double[]{6, 7, 8, 5, 9}) {
            Location probe = p.getLocation().subtract(flat.clone().multiply(d));
            centre = groundAt(w, probe.getX(), p.getLocation().getY(), probe.getZ());
            if (centre != null) break;
        }
        if (centre == null) {
            rumble(p);
            return;
        }
        final Location c = centre.clone();
        final BlockData rock = ceilingData(p);
        for (int i = 0; i < 8; i++) {
            later(i * 5, () -> {
                p.spawnParticle(Particle.FALLING_DUST, c.clone().add(0, 3, 0), 16, 1.5, 0.3, 1.5, 0, rock);
                p.playSound(c, "minecraft:block.gravel.fall", SoundCategory.MASTER, 0.9f, 0.5f);
            });
        }
        later(30, () -> {
            p.playSound(c, "minecraft:entity.generic.explode", SoundCategory.MASTER, 0.5f, 0.5f);
            p.playSound(c, "minecraft:block.gravel.break", SoundCategory.MASTER, 1.0f, 0.5f);
            p.playSound(c, "minecraft:block.stone.break", SoundCategory.MASTER, 1.0f, 0.5f);
            p.spawnParticle(Particle.LARGE_SMOKE, c.clone().add(0, 1.5, 0), 40, 1.2, 1.0, 1.2, 0.02);
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 40, 0, false, false, false));
            Material[] mats = {Material.COBBLESTONE, Material.GRAVEL, Material.STONE, Material.COBBLESTONE};
            ThreadLocalRandom rr = ThreadLocalRandom.current();
            Location pl = p.getLocation();
            for (int x = -2; x <= 2; x++) {
                for (int y = 0; y <= 3; y++) {
                    for (int z = -2; z <= 2; z++) {
                        if (faked.size() >= 160) break;
                        Block b = w.getBlockAt(c.getBlockX() + x, c.getBlockY() + y, c.getBlockZ() + z);
                        if (!b.isPassable() || b.isLiquid()) continue;
                        if (b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(pl) < 2.5 * 2.5) continue;
                        p.sendBlockChange(b.getLocation(), mats[rr.nextInt(mats.length)].createBlockData());
                        faked.add(b.getLocation());
                    }
                }
            }
        });
        later(60, () -> p.playSound(p.getLocation(), "minecraft:block.pointed_dripstone.drip_water", SoundCategory.MASTER, 1.0f, 0.7f));
        later(30 + cfg().getInt("cave.collapse-seconds", 8) * 20, () -> restoreFaked(p));
    }

    // ------------------------------------------------------------------ the scares

    private void stalker(Player p) {
        Location at = findSpot(p, 15, 24, true, Math.toRadians(70));
        if (at == null) at = findSpot(p, 12, 22, false, 0);
        if (at == null) return;
        addGhost(p, Type.STALKER, at, 500);
        p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 0.8f, 0.6f);
    }

    private void behind(Player p) {
        Vector flat = p.getEyeLocation().getDirection().setY(0);
        if (flat.lengthSquared() < 0.01) flat = new Vector(0, 0, 1);
        flat.normalize();
        Location at = null;
        for (double d : new double[]{2.6, 3.4, 4.5}) {
            Location probe = p.getLocation().subtract(flat.clone().multiply(d));
            at = groundAt(p.getWorld(), probe.getX(), p.getLocation().getY(), probe.getZ());
            if (at != null) break;
        }
        if (at == null) return;
        addGhost(p, Type.BEHIND, at, 90);
        p.playSound(at, Sound.ENTITY_PLAYER_BREATH, 0.9f, 0.7f);
    }

    private void rush(Player p) {
        rushGen(p, 0);
    }

    /** gen > 0 means it came back after the last one vanished (it does not stop). */
    private void rushGen(Player p, int gen) {
        Location at = findSpot(p, 16, 22, true, Math.toRadians(55));
        if (at == null) return;
        Ghost g = addGhost(p, Type.RUSH, at, 120);
        if (g == null) return;
        g.gen = gen;
        p.playSound(at, Sound.ENTITY_WARDEN_NEARBY_CLOSER, 1.0f, 0.8f - gen * 0.1f);
    }

    private void crowd(Player p) {
        int n = 4 + intensity * 3;
        int made = 0;
        for (int i = 0; i < n * 2 && made < n; i++) {
            Location at = findSpot(p, 8, 13, false, 0);
            if (at == null) continue;
            if (addGhost(p, Type.CROWD, at, 140 + ThreadLocalRandom.current().nextInt(40)) != null) made++;
        }
        if (made > 0) p.playSound(p.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.6f, 0.5f);
    }

    /** Fills in {player} {x} {y} {z} {item} {health} {world}. All of it is the victim's own in-game data, shown only to them. */
    private String fill(Player p, String text) {
        Location l = p.getLocation();
        Material held = p.getInventory().getItemInMainHand().getType();
        String item = held == Material.AIR ? "nothing" : held.name().toLowerCase().replace('_', ' ');
        return text.replace("{player}", targetName)
                .replace("{x}", String.valueOf(l.getBlockX()))
                .replace("{y}", String.valueOf(l.getBlockY()))
                .replace("{z}", String.valueOf(l.getBlockZ()))
                .replace("{item}", item)
                .replace("{health}", String.valueOf((int) Math.ceil(p.getHealth())))
                .replace("{world}", p.getWorld().getName());
    }

    private void whisper(Player p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int kind = r.nextInt(5);
        List<String> chat = cfg().getStringList("messages.chat");
        List<String> bar = cfg().getStringList("messages.action-bar");
        List<String> join = cfg().getStringList("messages.fake-join");
        List<String> personal = cfg().getStringList("messages.personal");
        if (kind == 4 && !personal.isEmpty()) {
            p.sendMessage(legacy(fill(p, personal.get(r.nextInt(personal.size())))));
        } else if (kind == 0 && !chat.isEmpty()) {
            p.sendMessage(legacy(fill(p, chat.get(r.nextInt(chat.size())))));
        } else if (kind == 1 && !bar.isEmpty()) {
            p.sendActionBar(legacy(fill(p, bar.get(r.nextInt(bar.size())))));
        } else if (kind == 2 && !join.isEmpty()) {
            p.sendMessage(Component.text(fill(p, join.get(r.nextInt(join.size()))), NamedTextColor.YELLOW));
        } else {
            List<String> sys = cfg().getStringList("messages.fake-system");
            if (!sys.isEmpty()) {
                p.sendMessage(Component.text(fill(p, sys.get(r.nextInt(sys.size()))), NamedTextColor.WHITE));
            }
        }
        p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 0.7f, 0.5f);
    }

    /** Weeping angel: only moves while you are NOT looking at it. Glows, so you can always see where it is. */
    private void angel(Player p) {
        Location at = findSpot(p, 18, 26, false, 0);
        if (at == null) return;
        Ghost g = addGhost(p, Type.ANGEL, at, 900);
        if (g == null) return;
        g.entity.setGlowing(true);
        p.playSound(at, "minecraft:entity.warden.nearby_closer", SoundCategory.MASTER, 0.7f, 0.6f);
    }

    /** A glowing figure far away (visible through walls) that slowly creeps closer and never vanishes when watched. */
    private void watcher(Player p) {
        Location at = findSpot(p, 26, 34, true, Math.toRadians(90));
        if (at == null) at = findSpot(p, 22, 32, false, 0);
        if (at == null) return;
        Ghost g = addGhost(p, Type.WATCHER, at, 700);
        if (g == null) return;
        g.entity.setGlowing(true);
        p.playSound(p.getLocation(), "minecraft:ambient.cave", SoundCategory.MASTER, 0.9f, 0.5f);
    }

    /** Nearby torches and lanterns go out (for them only), the world goes quiet and then something stands behind them. */
    private void lightsOut(Player p) {
        dimLights(p, 14);
        p.stopAllSounds();
        silentUntil = ticks + 160;
        nextScare = Math.max(nextScare, System.currentTimeMillis() + 9000L);
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 140, 0, false, false, false));
        later(30, () -> p.playSound(p.getLocation(), "minecraft:entity.warden.heartbeat", SoundCategory.MASTER, 1.0f, 0.7f));
        later(60, () -> p.playSound(p.getLocation(), "minecraft:entity.warden.heartbeat", SoundCategory.MASTER, 1.0f, 0.8f));
        later(84, () -> p.playSound(p.getLocation(), "minecraft:entity.warden.heartbeat", SoundCategory.MASTER, 1.0f, 0.9f));
        later(100, () -> behind(p));
        later(150, () -> restoreLights(p));
    }

    /** Total silence for a few seconds, a breath right behind them, and then it appears. */
    private void silence(Player p) {
        p.stopAllSounds();
        silentUntil = ticks + 150;
        nextScare = Math.max(nextScare, System.currentTimeMillis() + 9000L);
        later(90, () -> {
            Vector flat = p.getEyeLocation().getDirection().setY(0);
            if (flat.lengthSquared() < 0.01) flat = new Vector(0, 0, 1);
            flat.normalize();
            Location at = p.getLocation().subtract(flat.multiply(1.5));
            p.playSound(at, "minecraft:entity.player.breath", SoundCategory.MASTER, 0.5f, 0.5f);
        });
        later(130, () -> behind(p));
    }

    // ------------------------------------------------------------------ 1.3 nightmare scares

    /** A still figure at the very edge of the render distance. No sound. Look at it for a moment and it is gone. */
    private void treeline(Player p) {
        Location at = findSpot(p, 34, 46, true, Math.toRadians(80));
        if (at == null) at = findSpot(p, 30, 42, false, 0);
        if (at == null) return;
        addGhost(p, Type.TREELINE, at, 320);
    }

    /** Your own double, far away, copying every move you make. Stand still and it turns around and walks to you. */
    private void mimic(Player p) {
        Location at = findSpot(p, 13, 17, false, 0);
        if (at == null) return;
        Ghost g = addGhost(p, Type.MIMIC, at, 520, p.getPlayerProfile());
        if (g == null) return;
        g.lastP = p.getLocation().clone();
        p.playSound(p.getLocation(), "minecraft:ambient.cave", SoundCategory.MASTER, 0.6f, 0.5f);
    }

    /** Knocking from outside, getting louder. Then a door opens right behind you. */
    private void knock(Player p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang = r.nextDouble() * Math.PI * 2;
        final Location at = p.getLocation().add(Math.cos(ang) * 6, 0.5, Math.sin(ang) * 6);
        for (int i = 0; i < 3; i++) later(i * 14, () -> p.playSound(at, "minecraft:entity.zombie.attack_wooden_door", SoundCategory.MASTER, 0.7f, 0.8f));
        for (int i = 0; i < 3; i++) later(70 + i * 9, () -> p.playSound(at, "minecraft:entity.zombie.attack_wooden_door", SoundCategory.MASTER, 1.0f, 0.7f));
        later(130, () -> {
            Vector flat = p.getEyeLocation().getDirection().setY(0);
            if (flat.lengthSquared() < 0.01) flat = new Vector(0, 0, 1);
            flat.normalize();
            p.playSound(p.getLocation().subtract(flat.multiply(2.5)), "minecraft:block.wooden_door.open", SoundCategory.MASTER, 1.0f, 0.8f);
        });
        if (intensity >= 2) later(170, () -> behind(p));
    }

    /** A creeper hisses right behind you. Nothing is there. Somehow that is worse. */
    private void hiss(Player p) {
        Vector flat = p.getEyeLocation().getDirection().setY(0);
        if (flat.lengthSquared() < 0.01) flat = new Vector(0, 0, 1);
        flat.normalize();
        Location at = p.getLocation().subtract(flat.multiply(1.8));
        p.playSound(at, "minecraft:entity.creeper.primed", SoundCategory.MASTER, 1.0f, 1.0f);
        later(70, () -> p.playSound(p.getLocation(), "minecraft:entity.player.breath", SoundCategory.MASTER, 0.5f, 0.5f));
    }

    /** Sounds that are slightly wrong: a warped villager, a growl in the dark, someone screaming far away. */
    private void wrong(Player p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int kind = r.nextInt(3);
        Location near = p.getLocation().add(r.nextDouble(-7, 7), 0, r.nextDouble(-7, 7));
        if (kind == 0) {
            p.playSound(near, "minecraft:entity.villager.ambient", SoundCategory.MASTER, 1.0f, 0.5f);
            later(24, () -> p.playSound(near, "minecraft:entity.villager.no", SoundCategory.MASTER, 0.8f, 0.5f));
        } else if (kind == 1) {
            p.playSound(near, "minecraft:entity.wolf.growl", SoundCategory.MASTER, 1.0f, 0.6f);
        } else {
            final Location far = p.getLocation().add(r.nextDouble(-20, 20), 0, r.nextDouble(-20, 20));
            p.playSound(far, "minecraft:entity.player.hurt", SoundCategory.MASTER, 1.0f, 0.8f);
            later(16, () -> p.playSound(far, "minecraft:entity.player.hurt", SoundCategory.MASTER, 1.0f, 0.7f));
            later(34, () -> p.playSound(far, "minecraft:entity.player.death", SoundCategory.MASTER, 1.0f, 0.8f));
        }
    }

    /** A low heartbeat that never quite goes away once the dread has built up. */
    private void dreadBeat(Player p) {
        if (quiet || intensity < 2) return;
        double d = dread();
        if (d >= 0.5 && live.isEmpty() && ticks - lastBeat >= (int) (200 - 140 * d)) {
            lastBeat = ticks;
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.25f + 0.35f * (float) d, 0.9f);
        }
    }

    /**
     * The end of a long haunting. Everything goes dark and silent, a heartbeat speeds up, a double appears far away,
     * something breathes behind you, your own name speaks in chat, and then it all arrives at once.
     * Only slow darkness pulses are used (no flashing).
     */
    private void finale(Player p) {
        dimLights(p, 16);
        p.stopAllSounds();
        silentUntil = ticks + 360;
        nextScare = System.currentTimeMillis() + 22000L;
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 280, 0, false, false, false));
        int t = 20;
        for (int i = 0; i < 8; i++) {
            final float pitch = 0.7f + i * 0.05f;
            later(t, () -> p.playSound(p.getLocation(), "minecraft:entity.warden.heartbeat", SoundCategory.MASTER, 1.0f, pitch));
            t += Math.max(6, 20 - i * 2);
        }
        later(80, () -> mimic(p));
        later(150, () -> {
            Vector flat = p.getEyeLocation().getDirection().setY(0);
            if (flat.lengthSquared() < 0.01) flat = new Vector(0, 0, 1);
            flat.normalize();
            p.playSound(p.getLocation().subtract(flat.multiply(1.2)), "minecraft:entity.player.breath", SoundCategory.MASTER, 0.8f, 0.5f);
        });
        later(190, () -> p.sendMessage(legacy("<" + targetName + "> look behind you")));
        later(215, () -> behind(p));
        later(265, () -> crowd(p));
        later(330, () -> {
            restoreLights(p);
            p.sendActionBar(legacy("&8you were never alone, " + targetName));
        });
    }

    private static boolean isLight(Material m) {
        return switch (m) {
            case TORCH, WALL_TORCH, SOUL_TORCH, SOUL_WALL_TORCH, REDSTONE_TORCH, REDSTONE_WALL_TORCH, LANTERN, SOUL_LANTERN -> true;
            default -> false;
        };
    }

    private void dimLights(Player p, int r) {
        World w = p.getWorld();
        Location c = p.getLocation();
        BlockData air = Material.AIR.createBlockData();
        int cx = c.getBlockX();
        int cy = c.getBlockY();
        int cz = c.getBlockZ();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = cy - 6; y <= cy + 6; y++) {
                    if (dimmed.size() >= 300) return;
                    Block b = w.getBlockAt(x, y, z);
                    if (isLight(b.getType())) {
                        Location l = b.getLocation();
                        p.sendBlockChange(l, air);
                        dimmed.add(l);
                    }
                }
            }
        }
    }

    private void restoreLights(Player p) {
        for (Location l : dimmed) {
            try {
                p.sendBlockChange(l, l.getBlock().getBlockData());
            } catch (Throwable ignored) {
            }
        }
        dimmed.clear();
    }

    private void soundScare(Player p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Vector flat = p.getEyeLocation().getDirection().setY(0);
        if (flat.lengthSquared() < 0.01) flat = new Vector(0, 0, 1);
        flat.normalize();
        int kind = r.nextInt(4);
        if (kind == 0) {
            // footsteps creeping up from behind
            for (int i = 0; i < 7; i++) {
                final double dist = 9 - i * 1.0;
                final Vector back = flat.clone();
                final boolean gravel = i % 2 == 0;
                later(i * 7, () -> {
                    Location at = p.getLocation().subtract(back.clone().multiply(dist));
                    p.playSound(at, gravel ? Sound.BLOCK_GRAVEL_STEP : Sound.BLOCK_STONE_STEP, 0.9f, 0.8f);
                });
            }
        } else if (kind == 1) {
            Location at = p.getLocation().add(r.nextDouble(-8, 8), 0, r.nextDouble(-8, 8));
            p.playSound(at, Sound.BLOCK_WOODEN_DOOR_OPEN, 1.0f, 0.8f);
            later(24, () -> p.playSound(at, Sound.BLOCK_WOODEN_DOOR_CLOSE, 1.0f, 0.8f));
        } else if (kind == 2) {
            Location at = p.getLocation().add(r.nextDouble(-6, 6), 0, r.nextDouble(-6, 6));
            p.playSound(at, Sound.BLOCK_CHEST_OPEN, 1.0f, 0.7f);
        } else {
            Location at = p.getLocation().subtract(flat.clone().multiply(2.5));
            p.playSound(at, Sound.ENTITY_ENDERMAN_STARE, 0.7f, 0.6f);
        }
    }

    private static final String[] CREEPY = {
            "minecraft:ambient.cave", "minecraft:entity.warden.ambient", "minecraft:entity.warden.sniff",
            "minecraft:entity.phantom.ambient", "minecraft:entity.zombie.ambient", "minecraft:entity.vex.ambient",
            "minecraft:entity.elder_guardian.ambient", "minecraft:entity.zombie_villager.ambient",
            "minecraft:entity.wither.ambient", "minecraft:entity.enderman.stare", "minecraft:entity.warden.nearby_close"
    };

    private long ambientGap() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int lo = intensity >= 3 ? 3 : (intensity == 2 ? 5 : 7);
        int hi = intensity >= 3 ? 8 : (intensity == 2 ? 14 : 20);
        return (lo + r.nextInt(hi - lo + 1)) * 1000L;
    }

    /** Sounds you hear in a cave: drips, rock shifting, something far away. Only used while they are underground. */
    private static final String[] CAVEY = {
            "minecraft:block.pointed_dripstone.drip_water", "minecraft:block.pointed_dripstone.drip_water",
            "minecraft:block.pointed_dripstone.drip_lava", "minecraft:block.gravel.fall",
            "minecraft:block.stone.hit", "minecraft:block.deepslate.hit", "minecraft:ambient.cave",
            "minecraft:block.sculk_sensor.clicking", "minecraft:entity.skeleton.ambient",
            "minecraft:entity.zombie.ambient", "minecraft:entity.warden.nearby_close", "minecraft:block.stone.fall"
    };

    private void ambient(Player p) {
        if (ticks < silentUntil) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = p.getLocation().add(r.nextDouble(-14, 14), 0, r.nextDouble(-14, 14));
        if (caveActive(p) && r.nextDouble() < 0.6) {
            Location near = p.getLocation().add(r.nextDouble(-9, 9), r.nextDouble(-1, 2), r.nextDouble(-9, 9));
            String snd = CAVEY[r.nextInt(CAVEY.length)];
            p.playSound(near, snd, SoundCategory.MASTER, 0.6f + intensity * 0.1f, 0.5f + (float) r.nextDouble(0.4));
            return;
        }
        if (ticks - lastDisc > 2400 && r.nextDouble() < 0.03 * intensity) {
            lastDisc = ticks;
            p.playSound(p.getLocation(), "minecraft:music_disc.11", SoundCategory.RECORDS, 0.5f, 1.0f);
        } else if (intensity >= 3 && r.nextDouble() < 0.35) {
            Vector flat = p.getEyeLocation().getDirection().setY(0);
            if (flat.lengthSquared() < 0.01) flat = new Vector(0, 0, 1);
            flat.normalize();
            Location behind = p.getLocation().subtract(flat.multiply(1.6));
            p.playSound(behind, "minecraft:entity.player.breath", SoundCategory.MASTER, 0.9f, 0.5f);
        } else {
            String snd = CREEPY[r.nextInt(CREEPY.length)];
            p.playSound(at, snd, SoundCategory.MASTER, 0.5f + intensity * 0.1f, 0.5f + (float) r.nextDouble(0.3));
        }
    }

    /** Drifting ash and soul particles (only they see them). */
    private void atmosphere(Player p) {
        if (intensity < 2 || ticks % 20 != 0) return;
        Location c = p.getLocation().add(0, 2.5, 0);
        if (caveActive(p)) {
            // loose dust trickling from the ceiling
            p.spawnParticle(Particle.FALLING_DUST, p.getEyeLocation().add(0, 1.2, 0), 5, 3, 0.2, 3, 0, ceilingData(p));
            return;
        }
        p.spawnParticle(Particle.ASH, c, 40, 8, 3, 8, 0);
        if (intensity >= 3) p.spawnParticle(Particle.SCULK_SOUL, c, 3, 6, 2, 6, 0.01);
    }

    private String pickSubtitle(Player p) {
        List<String> l = cfg().getStringList("scares.jumpscare.subtitles");
        if (l.isEmpty()) return "";
        return fill(p, l.get(ThreadLocalRandom.current().nextInt(l.size())));
    }

    /** The big scare: sound, skull flash and a darkness pulse. Has a cooldown so it can't be spammed. */
    boolean jumpscare(Player p) {
        long now = System.currentTimeMillis();
        if (now - lastJumpscare < cfg().getLong("scares.jumpscare.cooldown-seconds", 6) * 1000L) return false;
        lastJumpscare = now;
        String title = cfg().getString("scares.jumpscare.title", "&4&l\u2620");
        if (title != null && !title.isBlank()) {
            p.showTitle(Title.title(legacy(title), legacy(pickSubtitle(p)),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(500), Duration.ofMillis(300))));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 0.7f);
        p.playSound(p.getLocation(), Sound.ENTITY_GHAST_SCREAM, 1.0f, 0.5f);
        String[] screams = {"minecraft:entity.enderman.scream", "minecraft:entity.warden.roar",
                "minecraft:block.sculk_shrieker.shriek", "minecraft:entity.elder_guardian.curse"};
        ThreadLocalRandom rr = ThreadLocalRandom.current();
        p.playSound(p.getLocation(), screams[rr.nextInt(screams.length)], SoundCategory.MASTER, 1.0f, 0.6f + 0.4f * rr.nextFloat());
        if (intensity >= 3 && now - lastAfter > 15000L && rr.nextDouble() < 0.4) {
            // it vanished... and then it is behind you again
            lastAfter = now;
            later(50, () -> behind(p));
        }
        if (intensity >= 2 && cfg().getBoolean("scares.jumpscare.darkness", true)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 50, 0, false, false, false));
        }
        if (intensity >= 3 && cfg().getBoolean("scares.jumpscare.blindness", false)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 14, 0, false, false, false));
        }
        if (cfg().getBoolean("scares.jumpscare.hurt-flash", false)) {
            p.playHurtAnimation(p.getLocation().getYaw());
        }
        return true;
    }

    // ------------------------------------------------------------------ ghosts

    private Ghost addGhost(Player p, Type type, Location at, int ttl) {
        return addGhost(p, type, at, ttl, null);
    }

    private Ghost addGhost(Player p, Type type, Location at, int ttl, PlayerProfile forced) {
        if (live.size() >= cfg().getInt("scares.max-ghosts", 18)) return null;
        PlayerProfile profile = forced != null ? forced : ghosts.pickProfile(p);
        Entity e = ghosts.spawn(at, profile, p);
        Ghost g = new Ghost(e, type, at.clone(), ttl);
        live.add(g);
        face(g, p.getEyeLocation());
        return g;
    }

    private void face(Ghost g, Location eye) {
        Vector v = eye.toVector().subtract(g.pos.toVector().add(new Vector(0, 1.6, 0)));
        Location l = g.pos.clone();
        l.setDirection(v);
        g.entity.setRotation(l.getYaw(), l.getPitch());
    }

    private void move(Ghost g, Location to) {
        g.pos = to.clone();
        g.entity.teleportAsync(to);
    }

    private void vanishFx(Player p, Location at, boolean loud) {
        Location c = at.clone().add(0, 1, 0);
        p.spawnParticle(Particle.SMOKE, c, 25, 0.3, 0.7, 0.3, 0.02);
        p.spawnParticle(Particle.SOUL, c, 6, 0.3, 0.6, 0.3, 0.01);
        if (loud) p.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.6f);
    }

    private void updateGhosts(Player p) {
        if (live.isEmpty()) return;
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();
        Iterator<Ghost> it = live.iterator();
        while (it.hasNext()) {
            Ghost g = it.next();
            g.age += 2;
            boolean gone = !g.entity.isValid()
                    || g.pos.getWorld() != p.getWorld()
                    || g.pos.distanceSquared(p.getLocation()) > 80 * 80;
            if (!gone) gone = step(g, p, eye, look);
            if (gone) {
                ghosts.remove(g.entity);
                it.remove();
            }
        }
    }

    /** Advances one ghost. Returns true if it should be removed. */
    private boolean step(Ghost g, Player p, Location eye, Vector look) {
        Vector to = g.pos.toVector().add(new Vector(0, 1.6, 0)).subtract(eye.toVector());
        double dist = to.length();
        if (dist < 0.001) return true;
        Vector dir = to.clone().multiply(1.0 / dist);
        double facing = look.dot(dir);
        if (g.type == Type.MIMIC && !g.closing) {
            // the double stares the same way you do, like a reflection, until it decides to turn
            g.entity.setRotation(p.getLocation().getYaw(), p.getLocation().getPitch());
        } else {
            face(g, eye);
        }

        switch (g.type) {
            case STALKER -> {
                boolean looked = facing > Math.cos(Math.toRadians(11)) && dist > 4 && g.age > 20 && p.hasLineOfSight(g.entity);
                if (looked) {
                    vanishFx(p, g.pos, true);
                    p.playSound(g.pos, Sound.ENTITY_ENDERMAN_STARE, 0.6f, 0.5f);
                    if (intensity >= 3 && ThreadLocalRandom.current().nextDouble() < 0.35) later(30, () -> behind(p));
                    return true;
                }
                if (g.age >= g.ttl) {
                    vanishFx(p, g.pos, false);
                    return true;
                }
            }
            case ANGEL -> {
                boolean seen = facing > Math.cos(Math.toRadians(62)) && p.hasLineOfSight(g.entity);
                if (dist <= 2.6) {
                    jumpscare(p);
                    vanishFx(p, g.pos, true);
                    return true;
                }
                if (!seen) {
                    double stepLen = intensity >= 3 ? 0.5 : 0.35;
                    Vector flat = to.clone().setY(0).multiply(-1);
                    if (flat.lengthSquared() > 0.0001) {
                        flat.normalize().multiply(stepLen);
                        Location next = g.pos.clone().add(flat);
                        Location ground = groundAt(next.getWorld(), next.getX(), p.getLocation().getY(), next.getZ());
                        if (ground != null) next = ground;
                        move(g, next);
                    }
                    if (g.age % 8 == 0) p.playSound(g.pos, "minecraft:block.stone.step", SoundCategory.MASTER, 0.8f, 0.5f);
                }
                if (g.age >= g.ttl) {
                    vanishFx(p, g.pos, false);
                    return true;
                }
            }
            case PEEKER -> {
                boolean looked = facing > Math.cos(Math.toRadians(20)) && g.age > 16 && p.hasLineOfSight(g.entity);
                if (!g.turned && (looked || dist < 7)) {
                    g.turned = true;
                    g.hideAt = g.age + 14;
                    p.playSound(g.pos, "minecraft:entity.enderman.stare", SoundCategory.MASTER, 0.5f, 0.5f);
                }
                if (g.turned) {
                    // it backs away into the dark
                    Vector away = to.clone().setY(0);
                    if (away.lengthSquared() > 0.0001) {
                        away.normalize().multiply(0.45);
                        Location next = g.pos.clone().add(away);
                        Location ground = groundAt(next.getWorld(), next.getX(), p.getLocation().getY(), next.getZ());
                        if (ground == null) {
                            vanishFx(p, g.pos, false);
                            return true;
                        }
                        move(g, ground);
                    }
                }
                if (g.age >= g.hideAt || g.age >= g.ttl) {
                    vanishFx(p, g.pos, false);
                    return true;
                }
            }
            case WATCHER -> {
                if (dist < 11) {
                    vanishFx(p, g.pos, true);
                    p.playSound(p.getLocation(), "minecraft:entity.warden.heartbeat", SoundCategory.MASTER, 1.0f, 0.8f);
                    later(36, () -> behind(p));
                    return true;
                }
                Vector flat = to.clone().setY(0).multiply(-1);
                if (flat.lengthSquared() > 0.0001) {
                    flat.normalize().multiply(0.2);
                    Location next = g.pos.clone().add(flat);
                    Location ground = groundAt(next.getWorld(), next.getX(), p.getLocation().getY(), next.getZ());
                    if (ground != null) next = ground;
                    move(g, next);
                }
                if (g.age >= g.ttl) {
                    vanishFx(p, g.pos, false);
                    return true;
                }
            }
            case BEHIND -> {
                if (!g.turned && facing > Math.cos(Math.toRadians(55))) {
                    g.turned = true;
                    if (intensity >= 2) {
                        // you turned around... it is right in your face
                        Vector flat = look.clone().setY(0);
                        if (flat.lengthSquared() < 0.01) flat = new Vector(0, 0, 1);
                        flat.normalize();
                        Location inFront = p.getLocation().add(flat.multiply(1.5));
                        move(g, inFront);
                        face(g, eye);
                        jumpscare(p);
                        g.hideAt = g.age + 8;
                    } else {
                        vanishFx(p, g.pos, true);
                        return true;
                    }
                }
                if (g.age >= g.hideAt || g.age >= g.ttl) {
                    vanishFx(p, g.pos, true);
                    return true;
                }
            }
            case TREELINE -> {
                // a silent figure at the edge of your vision. Look at it for a second and it is simply gone.
                boolean looked = facing > Math.cos(Math.toRadians(9)) && g.age > 30 && p.hasLineOfSight(g.entity);
                if (looked) g.watch += 2;
                if (g.watch >= 26 || dist < 18 || g.age >= g.ttl) return true;
            }
            case MIMIC -> {
                Location pl = p.getLocation();
                if (g.lastP == null) g.lastP = pl.clone();
                double mdx = pl.getX() - g.lastP.getX();
                double mdz = pl.getZ() - g.lastP.getZ();
                g.lastP = pl.clone();
                if (!g.closing) {
                    // it copies every step you take
                    if (Math.abs(mdx) + Math.abs(mdz) > 0.001) {
                        Location next = g.pos.clone().add(mdx, 0, mdz);
                        Location ground = groundAt(next.getWorld(), next.getX(), g.pos.getY(), next.getZ());
                        if (ground != null) move(g, ground);
                    }
                    if (Math.abs(mdx) + Math.abs(mdz) < 0.002) g.watch += 2; else g.watch = Math.max(0, g.watch - 2);
                    // ...until you stop, or get close. Then it turns around.
                    if ((g.age >= 200 && (g.watch >= 30 || g.age >= 360)) || dist < 7) {
                        g.closing = true;
                        p.playSound(g.pos, "minecraft:entity.enderman.stare", SoundCategory.MASTER, 0.7f, 0.5f);
                    }
                } else {
                    boolean seen = facing > Math.cos(Math.toRadians(40)) && p.hasLineOfSight(g.entity);
                    if (dist <= 2.8) {
                        jumpscare(p);
                        vanishFx(p, g.pos, true);
                        return true;
                    }
                    if (!seen) {
                        Vector flat = to.clone().setY(0).multiply(-1);
                        if (flat.lengthSquared() > 0.0001) {
                            flat.normalize().multiply(0.6);
                            Location next = g.pos.clone().add(flat);
                            Location ground = groundAt(next.getWorld(), next.getX(), p.getLocation().getY(), next.getZ());
                            if (ground != null) next = ground;
                            move(g, next);
                        }
                    }
                }
                if (g.age >= g.ttl) {
                    vanishFx(p, g.pos, false);
                    return true;
                }
            }
            case RUSH -> {
                if (dist <= 2.2) {
                    jumpscare(p);
                    vanishFx(p, g.pos, true);
                    if (intensity >= 3 && g.gen < 2) {
                        // it does not stop. It comes again.
                        final int next = g.gen + 1;
                        later(24, () -> rushGen(p, next));
                    }
                    return true;
                }
                double speed = cfg().getDouble("scares.rush-speed", 1.5);
                Vector flat = to.clone().setY(0).multiply(-1);
                if (flat.lengthSquared() > 0.0001) {
                    flat.normalize().multiply(speed);
                    Location next = g.pos.clone().add(flat);
                    Location ground = groundAt(next.getWorld(), next.getX(), p.getLocation().getY(), next.getZ());
                    if (ground != null) next = ground;
                    move(g, next);
                }
                if (g.age >= g.ttl) return true;
            }
            case CROWD -> {
                if (g.age >= g.ttl) g.closing = true;
                if (g.closing) {
                    Vector flat = to.clone().setY(0).multiply(-1);
                    if (flat.lengthSquared() > 0.0001) {
                        flat.normalize().multiply(0.45);
                        move(g, g.pos.clone().add(flat));
                    }
                    if (dist < 2.8) {
                        if (intensity >= 3) jumpscare(p);
                        vanishFx(p, g.pos, false);
                        return true;
                    }
                }
            }
            default -> {
                return g.age >= g.ttl;
            }
        }
        return false;
    }

    private void heartbeat(Player p) {
        if (live.isEmpty()) return;
        double nearest = Double.MAX_VALUE;
        for (Ghost g : live) nearest = Math.min(nearest, g.pos.distance(p.getLocation()));
        if (nearest > (intensity >= 3 ? 26 : 14)) return;
        int gap = (int) Math.max(8, nearest * 2 + 6);
        if (ticks - lastBeat >= gap) {
            lastBeat = ticks;
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.8f, 1.0f);
        }
    }

    /** The target hit a ghost: it vanishes (and sometimes comes back behind them). */
    void hit(Player p, Entity e) {
        Iterator<Ghost> it = live.iterator();
        while (it.hasNext()) {
            Ghost g = it.next();
            if (g.entity.equals(e)) {
                vanishFx(p, g.pos, true);
                ghosts.remove(g.entity);
                it.remove();
                if (intensity >= 2 && ThreadLocalRandom.current().nextDouble() < 0.4) later(40, () -> behind(p));
                return;
            }
        }
    }

    // ------------------------------------------------------------------ finding places

    private Location findSpot(Player p, double minR, double maxR, boolean inFront, double spreadRad) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location c = p.getLocation();
        Vector look = p.getEyeLocation().getDirection();
        double base = Math.atan2(look.getZ(), look.getX());
        for (int i = 0; i < 14; i++) {
            double ang = inFront ? base + (r.nextDouble() * 2 - 1) * spreadRad : r.nextDouble() * Math.PI * 2;
            double rad = minR + r.nextDouble() * (maxR - minR);
            Location g = groundAt(p.getWorld(), c.getX() + Math.cos(ang) * rad, c.getY(), c.getZ() + Math.sin(ang) * rad);
            if (g != null) return g;
        }
        return null;
    }

    /** Standing position (solid block below, two free blocks above) near the given height, or null. */
    private static Location groundAt(World w, double x, double yHint, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        int top = (int) Math.floor(yHint) + 4;
        int bottom = (int) Math.floor(yHint) - 5;
        for (int y = top; y >= bottom; y--) {
            Block b = w.getBlockAt(bx, y, bz);
            if (b.getType().isSolid() && !b.isLiquid()) {
                Block a1 = w.getBlockAt(bx, y + 1, bz);
                Block a2 = w.getBlockAt(bx, y + 2, bz);
                if (a1.isPassable() && a2.isPassable() && !a1.isLiquid()) {
                    return new Location(w, bx + 0.5, y + 1, bz + 0.5);
                }
            }
        }
        return null;
    }
}
