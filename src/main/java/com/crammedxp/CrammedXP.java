package com.crammedxp;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CrammedXP extends JavaPlugin {

    /** Immutable snapshot of the config, swapped wholesale on reload. */
    public record Settings(boolean active,
                           Set<EntityDamageEvent.DamageCause> causes,
                           Set<EntityType> excluded,
                           int requirePlayerWithin) {

        public boolean isExcluded(EntityType type) {
            return excluded.contains(type);
        }
    }

    private volatile Settings settings;
    private XpTable xpTable;

    @Override
    public void onEnable() {
        xpTable = new XpTable(getLogger());
        reloadSettings();
        getServer().getPluginManager().registerEvents(new CrammingListener(this), this);
        getLogger().info("[CrammedXP] Enabled. Causes: " + describeCauses() + ".");
    }

    public Settings settings() {
        return settings;
    }

    public XpTable xpTable() {
        return xpTable;
    }

    private String describeCauses() {
        List<String> names = new ArrayList<>();
        for (EntityDamageEvent.DamageCause cause : settings.causes()) {
            names.add(cause.name().toLowerCase(Locale.ROOT));
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    /**
     * Reload from disk, adding any keys this version introduced.
     *
     * <p>Bukkit only writes a config file that does not already exist, so an
     * upgraded server keeps its original file forever and silently misses new
     * settings. Merging the shipped defaults in avoids that, without touching any
     * value the admin has changed.
     */
    public void reloadSettings() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) {
            saveResource("config.yml", false);
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        InputStream stream = getResource("config.yml");
        if (stream != null) {
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                YamlConfiguration shipped = YamlConfiguration.loadConfiguration(reader);
                int added = 0;
                for (String key : shipped.getKeys(true)) {
                    if (!shipped.isConfigurationSection(key) && !config.contains(key)) {
                        config.set(key, shipped.get(key));
                        added++;
                    }
                }
                if (added > 0) {
                    config.save(file);
                    getLogger().info("[CrammedXP] config.yml: added " + added + " new setting(s).");
                }
            } catch (IOException e) {
                getLogger().warning("[CrammedXP] Could not merge config defaults: " + e.getMessage());
            }
        }

        boolean active = config.getBoolean("enabled", true);

        Set<EntityDamageEvent.DamageCause> causes =
                EnumSet.noneOf(EntityDamageEvent.DamageCause.class);
        for (String name : config.getStringList("damage-causes")) {
            try {
                causes.add(EntityDamageEvent.DamageCause.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                getLogger().warning("[CrammedXP] Unknown damage cause in config: " + name);
            }
        }
        if (causes.isEmpty()) {
            causes.add(EntityDamageEvent.DamageCause.CRAMMING);
        }

        Set<EntityType> excluded = EnumSet.noneOf(EntityType.class);
        for (String name : config.getStringList("excluded-entities")) {
            try {
                excluded.add(EntityType.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                getLogger().warning("[CrammedXP] Unknown entity type in config: " + name);
            }
        }

        int radius = config.getInt("require-player-within", 0);

        this.settings = new Settings(active, causes, excluded, radius);
        xpTable.load(config.getConfigurationSection("xp"));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("crammedxp.admin")) {
                sender.sendMessage("§cYou do not have permission to do that.");
                return true;
            }
            reloadSettings();
            sender.sendMessage("§aCrammedXP reloaded. Causes: §f" + describeCauses());
            return true;
        }
        sender.sendMessage("§7CrammedXP — mobs killed by cramming drop experience.");
        sender.sendMessage("§7Status: " + (settings.active() ? "§aon" : "§coff")
                + " §7· causes: §f" + describeCauses());
        sender.sendMessage("§7Usage: §f/crammedxp reload");
        return true;
    }
}
