package com.crammedxp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.MagmaCube;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Zombie;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.entity.EntityType;

/**
 * How much experience a mob should drop.
 *
 * <p>The values mirror what the mob would have given had a player killed it, so a
 * cramming farm pays the same as killing the mobs by hand rather than becoming a
 * better source of experience than fighting.
 *
 * <p>Bukkit cannot be asked for this. {@code EntityDeathEvent.getDroppedExp()}
 * returns zero for any non-player kill - that is the whole reason cramming drops
 * nothing - so the amount has to come from a table.
 */
public final class XpTable {

    /** A fixed amount, or a range rolled per death the way vanilla does. */
    private record Amount(int min, int max) {
        int roll() {
            return min >= max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
        }
    }

    private final Logger logger;
    private final Map<EntityType, Amount> overrides = new EnumMap<>(EntityType.class);
    private Amount defaultHostile = new Amount(5, 5);
    private Amount defaultPassive = new Amount(1, 3);
    private double multiplier = 1.0d;
    private int cap = 0;
    private boolean babyAnimalsGiveXp = false;

    public XpTable(Logger logger) {
        this.logger = logger;
    }

    public void load(ConfigurationSection section) {
        overrides.clear();
        if (section == null) {
            return;
        }
        multiplier = section.getDouble("multiplier", 1.0d);
        cap = section.getInt("max-per-death", 0);
        babyAnimalsGiveXp = section.getBoolean("baby-animals-give-xp", false);
        defaultHostile = parse(section.getString("default-hostile", "5"), new Amount(5, 5));
        defaultPassive = parse(section.getString("default-passive", "1-3"), new Amount(1, 3));

        ConfigurationSection perEntity = section.getConfigurationSection("per-entity");
        if (perEntity == null) {
            return;
        }
        for (String key : perEntity.getKeys(false)) {
            EntityType type;
            try {
                type = EntityType.valueOf(key.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // A name from a different game version. Skip it rather than refuse
                // to load the whole config.
                logger.warning("[CrammedXP] Unknown entity type in config: " + key);
                continue;
            }
            overrides.put(type, parse(perEntity.getString(key, "0"), new Amount(0, 0)));
        }
    }

    /** Accepts "5" or "1-3". */
    private Amount parse(String raw, Amount fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            String text = raw.trim();
            if (text.contains("-")) {
                String[] parts = text.split("-", 2);
                int min = Integer.parseInt(parts[0].trim());
                int max = Integer.parseInt(parts[1].trim());
                return new Amount(Math.max(0, Math.min(min, max)), Math.max(0, Math.max(min, max)));
            }
            int value = Integer.parseInt(text);
            return new Amount(Math.max(0, value), Math.max(0, value));
        } catch (NumberFormatException e) {
            logger.warning("[CrammedXP] Could not read xp value '" + raw + "', using default.");
            return fallback;
        }
    }

    /**
     * Experience for this entity, after multiplier and cap.
     *
     * @return the amount, which may legitimately be zero
     */
    public int xpFor(LivingEntity entity) {
        int base = baseFor(entity);
        int scaled = (int) Math.round(base * multiplier);
        if (cap > 0) {
            scaled = Math.min(scaled, cap);
        }
        return Math.max(0, scaled);
    }

    private int baseFor(LivingEntity entity) {
        EntityType type = entity.getType();

        // Slimes and magma cubes scale with size in vanilla, and an override would
        // otherwise pay the same for a tiny slime as for a giant one.
        if (entity instanceof Slime slime && !overrides.containsKey(type)) {
            return sizeXp(slime.getSize());
        }
        if (entity instanceof MagmaCube cube && !overrides.containsKey(type)) {
            return sizeXp(cube.getSize());
        }

        // Baby animals give nothing in vanilla. Baby zombies and the like give more
        // than adults, which the hostile default already approximates closely
        // enough without special-casing every variant.
        if (isBabyAnimal(entity) && !babyAnimalsGiveXp) {
            return 0;
        }

        Amount override = overrides.get(type);
        if (override != null) {
            return override.roll();
        }
        return isHostile(entity) ? defaultHostile.roll() : defaultPassive.roll();
    }

    private static int sizeXp(int size) {
        // Vanilla: 1, 2 and 4 for sizes 1, 2 and 4.
        return Math.max(1, size);
    }

    private static boolean isBabyAnimal(LivingEntity entity) {
        // Baby zombies and piglins are hostile and do drop xp in vanilla, so the
        // no-xp-for-babies rule is about animals only.
        if (entity instanceof Zombie) {
            return false;
        }
        return entity instanceof Ageable ageable && !ageable.isAdult();
    }

    /**
     * Rough hostile check. Anything that can target and attack counts; the rest
     * falls back to the passive default.
     */
    private static boolean isHostile(LivingEntity entity) {
        if (!(entity instanceof Mob)) {
            return false;
        }
        return entity instanceof org.bukkit.entity.Monster
                || entity instanceof org.bukkit.entity.Slime
                || entity instanceof org.bukkit.entity.Ghast
                || entity instanceof org.bukkit.entity.Phantom
                || entity instanceof org.bukkit.entity.Shulker
                || entity instanceof org.bukkit.entity.Hoglin;
    }
}
