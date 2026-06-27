package dev.steveai.storage;

import dev.steveai.agent.AgentManager;
import dev.steveai.agent.SteveAgent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public final class AgentStore {
    private final JavaPlugin plugin;
    private final File file;

    public AgentStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "agents.yml");
    }

    public void load(AgentManager manager) {
        manager.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection agents = yaml.getConfigurationSection("agents");
        if (agents == null) {
            return;
        }
        for (String name : agents.getKeys(false)) {
            ConfigurationSection section = agents.getConfigurationSection(name);
            if (section == null) {
                continue;
            }
            if (section.getBoolean("dead", false)) {
                continue;
            }
            World world = Bukkit.getWorld(section.getString("world", "world"));
            if (world == null) {
                plugin.getLogger().warning("Skipping agent " + name + ": world missing.");
                continue;
            }
            Location location = new Location(
                world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw"),
                (float) section.getDouble("pitch")
            );
            UUID ownerId = UUID.fromString(section.getString("owner"));
            SteveAgent agent = manager.createLoaded(name, ownerId, location);
            if (section.contains("npc-id")) {
                agent.setCitizensNpcId(section.getInt("npc-id"));
            }
            agent.setFollowing(section.getBoolean("following", false));
            agent.setProtecting(section.getBoolean("protecting", false));
            agent.setSitting(section.getBoolean("sitting", false));
            agent.setDead(section.getBoolean("dead", false));
            agent.setWounded(section.getBoolean("wounded", false));
            Material equipped = Material.matchMaterial(section.getString("equipped", "AIR"));
            agent.setEquippedItem(equipped == null ? Material.AIR : equipped);
            ConfigurationSection inv = section.getConfigurationSection("inventory");
            if (inv != null) {
                for (String materialName : inv.getKeys(false)) {
                    Material material = Material.matchMaterial(materialName);
                    if (material != null) {
                        agent.addItem(material, inv.getInt(materialName));
                    }
                }
            }
        }
    }

    public void save(AgentManager manager) {
        plugin.getDataFolder().mkdirs();
        YamlConfiguration yaml = new YamlConfiguration();
        for (SteveAgent agent : manager.all()) {
            String base = "agents." + agent.getName() + ".";
            Location location = agent.getLocation();
            yaml.set(base + "owner", agent.getOwnerId().toString());
            yaml.set(base + "world", location.getWorld().getName());
            yaml.set(base + "x", location.getX());
            yaml.set(base + "y", location.getY());
            yaml.set(base + "z", location.getZ());
            yaml.set(base + "yaw", location.getYaw());
            yaml.set(base + "pitch", location.getPitch());
            if (agent.getCitizensNpcId().isPresent()) {
                yaml.set(base + "npc-id", agent.getCitizensNpcId().getAsInt());
            }
            yaml.set(base + "following", agent.isFollowing());
            yaml.set(base + "protecting", agent.isProtecting());
            yaml.set(base + "sitting", agent.isSitting());
            yaml.set(base + "dead", agent.isDead());
            yaml.set(base + "wounded", agent.isWounded());
            yaml.set(base + "equipped", agent.getEquippedItem().name());
            agent.inventoryView().forEach((material, amount) -> yaml.set(base + "inventory." + material.name(), amount));
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save agents.yml: " + ex.getMessage());
        }
    }

    public void saveAsync(AgentManager manager) {
        plugin.getDataFolder().mkdirs();
        YamlConfiguration yaml = new YamlConfiguration();
        for (SteveAgent agent : manager.all()) {
            String base = "agents." + agent.getName() + ".";
            Location location = agent.getLocation();
            yaml.set(base + "owner", agent.getOwnerId().toString());
            yaml.set(base + "world", location.getWorld().getName());
            yaml.set(base + "x", location.getX());
            yaml.set(base + "y", location.getY());
            yaml.set(base + "z", location.getZ());
            yaml.set(base + "yaw", location.getYaw());
            yaml.set(base + "pitch", location.getPitch());
            if (agent.getCitizensNpcId().isPresent()) {
                yaml.set(base + "npc-id", agent.getCitizensNpcId().getAsInt());
            }
            yaml.set(base + "following", agent.isFollowing());
            yaml.set(base + "protecting", agent.isProtecting());
            yaml.set(base + "sitting", agent.isSitting());
            yaml.set(base + "dead", agent.isDead());
            yaml.set(base + "wounded", agent.isWounded());
            yaml.set(base + "equipped", agent.getEquippedItem().name());
            agent.inventoryView().forEach((material, amount) -> yaml.set(base + "inventory." + material.name(), amount));
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                yaml.save(file);
            } catch (IOException ex) {
                plugin.getLogger().severe("Could not save agents.yml asynchronously: " + ex.getMessage());
            }
        });
    }
}