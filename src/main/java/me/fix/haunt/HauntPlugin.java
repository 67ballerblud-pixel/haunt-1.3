package me.fix.haunt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /haunt: fake players, jumpscares and creepy ambience that only the haunted player can see.
 * Built on the entity scheduler so it works on Paper and Folia.
 */
public final class HauntPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private final Map<UUID, Haunt> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> optedOut = ConcurrentHashMap.newKeySet();
    private Ghosts ghosts;
    private File optOutFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ghosts = new Ghosts(this);
        optOutFile = new File(getDataFolder(), "optout.yml");
        loadOptOut();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("haunt").setExecutor(this);
        getCommand("haunt").setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Haunt h = sessions.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (h != null) {
                try {
                    h.end(p);
                } catch (Throwable ignored) {
                    // shutting down; fake players are not persistent anyway
                }
            }
        }
    }

    // ------------------------------------------------------------------ sessions

    private static void msg(CommandSender s, String text) {
        s.sendMessage(Component.text("[Haunt] ", NamedTextColor.DARK_RED).append(Component.text(text, NamedTextColor.GRAY)));
    }

    /** Starts (or restarts) a haunting. Safe to call from any thread. */
    private void startHaunt(CommandSender by, Player target, int seconds, int intensity) {
        target.getScheduler().run(this, t -> {
            Haunt old = sessions.remove(target.getUniqueId());
            if (old != null) old.end(target);
            Haunt h = new Haunt(this, ghosts, target, seconds, intensity, false);
            sessions.put(target.getUniqueId(), h);
            h.start(target);
        }, null);
        getLogger().info(by.getName() + " is haunting " + target.getName() + " for " + seconds + "s at intensity " + intensity);
    }

    /**
     * Runs one scare on a player. If they are being haunted it uses that haunting; if not, it starts a quiet one-shot
     * session (no night, no rain, no random scares) that ends by itself when the scare is over.
     * typeArg is a scare name, "random" or "cave". Returns false if the scare name is unknown.
     */
    private boolean scareNow(CommandSender by, Player t, String typeArg, int intensity) {
        final boolean random = typeArg.equals("random");
        final boolean cave = typeArg.equals("cave");
        Haunt.Type parsed = null;
        if (!random && !cave) {
            try {
                parsed = Haunt.Type.valueOf(typeArg.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        final Haunt.Type type = parsed;
        t.getScheduler().run(this, task -> {
            Haunt h = sessions.get(t.getUniqueId());
            if (h == null || h.ended()) {
                h = new Haunt(this, ghosts, t, getConfig().getInt("scare-command.max-seconds", 90), intensity, true);
                sessions.put(t.getUniqueId(), h);
                h.start(t);
            }
            if (random) h.scareRandom(t);
            else if (cave) h.scareCave(t);
            else h.scare(t, type);
        }, null);
        getLogger().info(by.getName() + " scared " + t.getName() + " (" + typeArg + ")");
        return true;
    }

    private static final String SCARE_NAMES = "random, cave, stalker, behind, rush, crowd, whisper, sound, jumpscare, angel, lightsout, "
            + "watcher, silence, treeline, mimic, knock, hiss, wrong, eyes, finale, cavein, tapping, peeker, rumble";

    /** Ends a haunting, cleaning up on the player's own thread. */
    void stopHaunt(UUID id) {
        Haunt h = sessions.remove(id);
        if (h == null) return;
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            p.getScheduler().run(this, t -> h.end(p), () -> h.end(null));
        } else {
            h.end(null);
        }
    }

    // ------------------------------------------------------------------ opt-out file

    private void loadOptOut() {
        if (!optOutFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(optOutFile);
        for (String s : y.getStringList("optout")) {
            try {
                optedOut.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveOptOut() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> out = new ArrayList<>();
        for (UUID id : optedOut) out.add(id.toString());
        y.set("optout", out);
        try {
            y.save(optOutFile);
        } catch (IOException e) {
            getLogger().warning("Could not save optout.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ command

    private static int parseSeconds(String s, int fallback) {
        try {
            String t = s.toLowerCase(Locale.ROOT);
            if (t.endsWith("h")) return Integer.parseInt(t.substring(0, t.length() - 1)) * 3600;
            if (t.endsWith("m")) return Integer.parseInt(t.substring(0, t.length() - 1)) * 60;
            if (t.endsWith("s")) return Integer.parseInt(t.substring(0, t.length() - 1));
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("optout")) {
            if (!(sender instanceof Player p)) {
                msg(sender, "Only players can opt out.");
                return true;
            }
            if (!p.hasPermission("haunt.optout")) {
                msg(sender, "You can't use that.");
                return true;
            }
            if (optedOut.remove(p.getUniqueId())) {
                msg(sender, "You can be haunted again. Good luck.");
            } else {
                optedOut.add(p.getUniqueId());
                stopHaunt(p.getUniqueId());
                msg(sender, "You will no longer be haunted.");
            }
            saveOptOut();
            return true;
        }

        if (!sender.hasPermission("haunt.use")) {
            msg(sender, "You don't have permission.");
            return true;
        }
        if (a.length == 0) {
            msg(sender, "/haunt <player> [duration] [intensity 1-3]  |  stop <player|all>  |  list  |  scare <player|all> [type] [intensity]  |  reload");
            return true;
        }

        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig();
                ghosts.prefetchSkins();
                msg(sender, "Config reloaded.");
                return true;
            }
            case "list" -> {
                if (sessions.isEmpty()) {
                    msg(sender, "Nobody is being haunted.");
                } else {
                    for (Haunt h : sessions.values()) {
                        msg(sender, h.targetName + " - " + (h.quiet() ? "one-shot scare" : "intensity " + h.intensity)
                                + ", " + h.ghostCount() + " ghost(s) right now");
                    }
                }
                return true;
            }
            case "stop" -> {
                if (a.length < 2) {
                    msg(sender, "Usage: /haunt stop <player|all>");
                    return true;
                }
                if (a[1].equalsIgnoreCase("all")) {
                    int n = sessions.size();
                    for (UUID id : new ArrayList<>(sessions.keySet())) stopHaunt(id);
                    msg(sender, "Stopped " + n + " haunting(s).");
                } else {
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t == null || !sessions.containsKey(t.getUniqueId())) {
                        msg(sender, "That player isn't being haunted.");
                    } else {
                        stopHaunt(t.getUniqueId());
                        msg(sender, "Stopped haunting " + t.getName() + ".");
                    }
                }
                return true;
            }
            case "scare" -> {
                // /haunt scare <player|all> [type] [intensity]  - works on anyone, no haunting needed
                if (a.length < 2) {
                    msg(sender, "Usage: /haunt scare <player|all> [type] [intensity 1-3]");
                    msg(sender, "Types: " + SCARE_NAMES);
                    return true;
                }
                String typeArg = a.length >= 3 ? a[2].toLowerCase(Locale.ROOT) : "random";
                int intensity = getConfig().getInt("scare-command.intensity", 2);
                if (a.length >= 4) {
                    try {
                        intensity = Integer.parseInt(a[3]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                intensity = Math.max(1, Math.min(3, intensity));
                List<Player> targets = new ArrayList<>();
                if (a[1].equalsIgnoreCase("all")) {
                    targets.addAll(Bukkit.getOnlinePlayers());
                } else {
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t == null) {
                        msg(sender, "That player isn't online.");
                        return true;
                    }
                    targets.add(t);
                }
                int done = 0;
                int skipped = 0;
                for (Player t : targets) {
                    if (t.hasPermission("haunt.bypass") || optedOut.contains(t.getUniqueId())) {
                        skipped++;
                        continue;
                    }
                    if (!scareNow(sender, t, typeArg, intensity)) {
                        msg(sender, "Unknown scare. Try: " + SCARE_NAMES);
                        return true;
                    }
                    done++;
                }
                if (targets.size() == 1) {
                    msg(sender, done == 1 ? "Scared " + targets.get(0).getName() + " (" + typeArg + ")."
                            : targets.get(0).getName() + " can't be scared (bypass or opted out).");
                } else {
                    msg(sender, "Scared " + done + " player(s)" + (skipped > 0 ? ", skipped " + skipped + " (bypass or opted out)." : "."));
                }
                return true;
            }
            default -> {
                // /haunt <player> [duration] [intensity]
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null) {
                    msg(sender, "That player isn't online.");
                    return true;
                }
                if (t.hasPermission("haunt.bypass") || optedOut.contains(t.getUniqueId())) {
                    msg(sender, t.getName() + " can't be haunted (bypass or opted out).");
                    return true;
                }
                int def = getConfig().getInt("defaults.duration-seconds", 120);
                int max = getConfig().getInt("defaults.max-duration-seconds", 1800);
                int seconds = Math.max(5, Math.min(max, a.length >= 2 ? parseSeconds(a[1], def) : def));
                int intensity = getConfig().getInt("defaults.intensity", 2);
                if (a.length >= 3) {
                    try {
                        intensity = Integer.parseInt(a[2]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                intensity = Math.max(1, Math.min(3, intensity));
                startHaunt(sender, t, seconds, intensity);
                msg(sender, "Haunting " + t.getName() + " for " + seconds + "s (intensity " + intensity + "). Only they can see it.");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            List<String> base = new ArrayList<>(List.of("stop", "list", "scare", "reload", "optout"));
            for (Player p : Bukkit.getOnlinePlayers()) base.add(p.getName());
            for (String s : base) if (s.toLowerCase(Locale.ROOT).startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("stop") || a[0].equalsIgnoreCase("scare"))) {
            out.add("all");
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("scare")) {
            out.add("random");
            out.add("cave");
            for (Haunt.Type t : Haunt.Type.values()) out.add(t.name().toLowerCase(Locale.ROOT));
        } else if (a.length == 4 && a[0].equalsIgnoreCase("scare")) {
            out.addAll(List.of("1", "2", "3"));
        } else if (a.length == 2) {
            out.addAll(List.of("60s", "2m", "5m", "10m"));
        } else if (a.length == 3) {
            out.addAll(List.of("1", "2", "3"));
        }
        return out;
    }

    // ------------------------------------------------------------------ keeping it contained

    private boolean isGhost(Entity e) {
        return ghosts != null && ghosts.isGhost(e);
    }

    /** Ghosts can't be hurt or used. Hitting one makes it vanish. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!isGhost(e.getEntity())) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p) {
            Haunt h = sessions.get(p.getUniqueId());
            if (h != null) h.hit(p, e.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnyDamage(EntityDamageEvent e) {
        if (isGhost(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (isGhost(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        if (isGhost(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stopHaunt(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        stopHaunt(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        // the fake players were left in the old world; restart them fresh if the haunt continues
        Haunt h = sessions.get(e.getPlayer().getUniqueId());
        if (h != null) h.clearGhosts();
    }
}
