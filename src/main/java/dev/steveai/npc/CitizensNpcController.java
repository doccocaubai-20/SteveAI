package dev.steveai.npc;

import dev.steveai.agent.SteveAgent;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CitizensNpcController {
    private final JavaPlugin plugin;

    public CitizensNpcController(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void spawn(SteveAgent agent, Location location) {
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, agent.getName());
        npc.spawn(location);
        prepareMortalNpc(npc);
        agent.setCitizensNpcId(npc.getId());
        agent.setLocation(location);
        agent.setDead(false);
        agent.remember("Survival Citizens NPC spawned with id " + npc.getId());
    }

    public void adopt(SteveAgent agent, NPC npc) {
        agent.setCitizensNpcId(npc.getId());
        prepareMortalNpc(npc);
        if (npc.isSpawned()) {
            agent.setLocation(npc.getEntity().getLocation());
        }
        agent.setDead(false);
        agent.remember("Adopted Citizens NPC id " + npc.getId());
    }

    public void destroy(SteveAgent agent) {
        find(agent).ifPresent(npc -> {
            npc.destroy();
            agent.remember("Citizens NPC destroyed.");
        });
    }

    public void moveNearOwner(SteveAgent agent, Player owner) {
        moveTo(agent, owner.getLocation().clone().add(1.5, 0, 1.5));
    }

    public void moveTo(SteveAgent agent, Location target) {
        Optional<NPC> found = find(agent);
        if (found.isEmpty()) {
            agent.setLocation(target);
            agent.remember("NPC missing; stored target location only.");
            return;
        }

        NPC npc = found.get();
        if (!npc.isSpawned()) {
            npc.spawn(agent.getLocation());
            prepareMortalNpc(npc);
        }
        npc.getNavigator().setTarget(target);
        agent.setLocation(npc.getEntity().getLocation());
        agent.remember("Citizens NPC navigating to "
            + target.getBlockX() + " " + target.getBlockY() + " " + target.getBlockZ());
    }

    public void teleportTo(SteveAgent agent, Location target) {
        find(agent).ifPresent(npc -> npc.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN));
        agent.setLocation(target);
        agent.remember("Teleported to " + target.getBlockX() + " " + target.getBlockY() + " " + target.getBlockZ());
    }

    public void equipMainHand(SteveAgent agent, Material material) {
        find(agent).filter(NPC::isSpawned).ifPresent(npc -> {
            Entity entity = npc.getEntity();
            if (entity instanceof LivingEntity livingEntity) {
                EntityEquipment equipment = livingEntity.getEquipment();
                if (equipment != null) {
                    equipment.setItemInMainHand(material == null || material.isAir() ? null : new ItemStack(material));
                }
            }
        });
        agent.setEquippedItem(material == null ? Material.AIR : material);
    }


    public Optional<Entity> currentEntity(SteveAgent agent) {
        return find(agent)
            .filter(NPC::isSpawned)
            .map(NPC::getEntity);
    }
    public Optional<Location> currentLocation(SteveAgent agent) {
        return find(agent)
            .filter(NPC::isSpawned)
            .map(npc -> npc.getEntity().getLocation());
    }

    public void syncLocation(SteveAgent agent) {
        currentLocation(agent).ifPresent(agent::setLocation);
    }

    public Optional<NPC> find(SteveAgent agent) {
        if (agent.getCitizensNpcId().isEmpty()) {
            return Optional.empty();
        }
        NPC npc = CitizensAPI.getNPCRegistry().getById(agent.getCitizensNpcId().getAsInt());
        return Optional.ofNullable(npc);
    }

    public Optional<NPC> findById(int id) {
        return Optional.ofNullable(CitizensAPI.getNPCRegistry().getById(id));
    }

    public Optional<NPC> findFirstByName(String name) {
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.getName().equalsIgnoreCase(name)) {
                return Optional.of(npc);
            }
        }
        return Optional.empty();
    }

    public List<NPC> findAllByName(String name) {
        List<NPC> matches = new ArrayList<>();
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.getName().equalsIgnoreCase(name)) {
                matches.add(npc);
            }
        }
        return matches;
    }

    public int cleanupByName(String name) {
        int removed = 0;
        for (NPC npc : findAllByName(name)) {
            npc.destroy();
            removed++;
        }
        return removed;
    }

    public boolean isAvailable() {
        return plugin.getServer().getPluginManager().isPluginEnabled("Citizens");
    }

    private void prepareMortalNpc(NPC npc) {
        npc.setProtected(false);
        makeMortal(npc.getEntity());
    }

    private void makeMortal(Entity entity) {
        if (entity == null) {
            return;
        }
        entity.setInvulnerable(false);
        if (entity instanceof LivingEntity livingEntity) {
            AttributeInstance maxHealth = livingEntity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(20.0D);
            }
        }
        if (entity instanceof Damageable damageable) {
            damageable.setHealth(Math.min(20.0D, damageable.getHealth()));
        }
    }
}