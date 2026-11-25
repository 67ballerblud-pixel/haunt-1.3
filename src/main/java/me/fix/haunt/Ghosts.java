package me.fix.haunt;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creates and removes the fake players. A fake player is a vanilla Mannequin entity (Minecraft 1.21.9+, found by
 * reflection so this builds against older APIs too). On servers without Mannequins it falls back to a posed armor
 * stand wearing a player head. Fake players are hidden from everybody except the haunted player.
 */
final class Ghosts {

    private final HauntPlugin plugin;
    final NamespacedKey key;
    private final Class<Entity> mannequinClass;
    private final Map<String, PlayerProfile> skinCache = new ConcurrentHashMap<>();
    private boolean warned;

    @SuppressWarnings("unchecked")
    Ghosts(HauntPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "haunt_ghost");
        Class<Entity> c = null;
        try {
            c = (Class<Entity>) Class.forName("org.bukkit.entity.Mannequin");
        } catch (Throwable ignored) {
            // older server: armor stand fallback
        }
        this.mannequinClass = c;
        plugin.getLogger().info(c != null ? "Fake players: using Mannequin entities." : "Fake players: Mannequin not available, using armor stands.");
        prefetchSkins();
    }

    boolean isGhost(Entity e) {
        return e != null && e.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Looks up the skins in the names list in the background so they are ready when needed. */
    void prefetchSkins() {
        for (String name : plugin.getConfig().getStringList("names")) {
            if (skinCache.containsKey(name.toLowerCase())) continue;
            try {
                PlayerProfile p = Bukkit.createProfile(name);
                p.update().thenAccept(done -> skinCache.put(name.toLowerCase(), done)).exceptionally(ex -> null);
            } catch (Throwable t) {
                // no skin for this name; it will just use the default skin
            }
        }
    }

    // ------------------------------------------------------------------ skins

    /** Picks a skin (profile) according to skin-mode. May return null (default skin). */
    PlayerProfile pickProfile(Player target) {
        String mode = plugin.getConfig().getString("skin-mode", "MIX").toUpperCase();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (mode.equals("MIX")) {
            String[] modes = {"DOPPELGANGER", "RANDOM_ONLINE", "NAMES"};
            mode = modes[rnd.nextInt(modes.length)];
        }
        switch (mode) {
            case "DOPPELGANGER":
                return target.getPlayerProfile();
            case "RANDOM_ONLINE": {
                List<Player> others = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) if (!p.getUniqueId().equals(target.getUniqueId())) others.add(p);
                return others.isEmpty() ? target.getPlayerProfile() : others.get(rnd.nextInt(others.size())).getPlayerProfile();
            }
            default: {
                List<PlayerProfile> ready = new ArrayList<>(skinCache.values());
                return ready.isEmpty() ? null : ready.get(rnd.nextInt(ready.size()));
            }
        }
    }

    // ------------------------------------------------------------------ spawning

    /** Spawns a fake player at the location. Must be called on the thread that owns that location. */
    Entity spawn(Location loc, PlayerProfile profile, Player target) {
        final boolean hide = !plugin.getConfig().getBoolean("visible-to-others", false);
        final boolean nameTag = plugin.getConfig().getBoolean("show-name-tag", true);
        Entity e = null;
        if (mannequinClass != null) {
            try {
                e = loc.getWorld().spawn(loc, mannequinClass, ent -> prepare(ent, hide));
                applyMannequinProfile(e, profile);
                tidyMannequin(e);
            } catch (Throwable t) {
                if (e != null) e.remove();
                e = null;
                if (!warned) {
                    warned = true;
                    plugin.getLogger().warning("Mannequin spawn failed (" + t + "), using armor stands.");
                }
            }
        }
        if (e == null) {
            e = loc.getWorld().spawn(loc, ArmorStand.class, as -> {
                prepare(as, hide);
                dressArmorStand(as, profile);
            });
        }
        if (nameTag && profile != null && profile.getName() != null) {
            e.customName(Component.text(profile.getName()));
            e.setCustomNameVisible(true);
        }
        if (hide) target.showEntity(plugin, e);
        return e;
    }

    private void prepare(Entity ent, boolean hide) {
        if (hide) ent.setVisibleByDefault(false);
        ent.setPersistent(false);
        ent.setInvulnerable(true);
        ent.setSilent(true);
        ent.setGravity(false);
        ent.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        if (ent instanceof LivingEntity le) le.setCollidable(false);
    }

    private void applyMannequinProfile(Entity e, PlayerProfile profile) {
        if (profile == null) return;
        try {
            Class<?> rp = Class.forName("io.papermc.paper.datacomponent.item.ResolvableProfile");
            Class<?> bd = Class.forName("io.papermc.paper.datacomponent.item.ResolvableProfile$Builder");
            Object builder = rp.getMethod("resolvableProfile").invoke(null);
            if (profile.getName() != null) bd.getMethod("name", String.class).invoke(builder, profile.getName());
            if (profile.getId() != null) bd.getMethod("uuid", UUID.class).invoke(builder, profile.getId());
            bd.getMethod("addProperties", Collection.class).invoke(builder, profile.getProperties());
            Object built = bd.getMethod("build").invoke(builder);
            mannequinClass.getMethod("setProfile", rp).invoke(e, built);
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Could not set a Mannequin skin (" + t + "). Fake players will use the default skin.");
            }
        }
    }

    /** Hide the default "NPC" description text and stop the mannequin being pushed around. */
    private void tidyMannequin(Entity e) {
        try {
            Method imm = mannequinClass.getMethod("setImmovable", boolean.class);
            imm.invoke(e, true);
        } catch (Throwable ignored) {
        }
        try {
            Method desc = mannequinClass.getMethod("setDescription", Component.class);
            desc.invoke(e, new Object[]{null});
        } catch (Throwable ignored) {
        }
    }

    private void dressArmorStand(ArmorStand as, PlayerProfile profile) {
        as.setArms(true);
        as.setBasePlate(false);
        as.setSmall(false);
        EntityEquipment eq = as.getEquipment();
        if (eq == null) return;
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        if (profile != null) sm.setPlayerProfile(profile);
        head.setItemMeta(sm);
        eq.setHelmet(head);
        eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE));
        eq.setLeggings(dyed(Material.LEATHER_LEGGINGS));
        eq.setBoots(dyed(Material.LEATHER_BOOTS));
    }

    private ItemStack dyed(Material m) {
        ItemStack it = new ItemStack(m);
        LeatherArmorMeta meta = (LeatherArmorMeta) it.getItemMeta();
        meta.setColor(Color.fromRGB(35, 35, 40));
        it.setItemMeta(meta);
        return it;
    }

    // ------------------------------------------------------------------ removing

    /** Removes a fake player on its own region thread (safe on Folia). */
    void remove(Entity e) {
        if (e == null) return;
        try {
            e.getScheduler().run(plugin, t -> e.remove(), null);
        } catch (Throwable t) {
            try {
                e.remove();
            } catch (Throwable ignored) {
            }
        }
    }
}
