package dev.steveai.listener;

import dev.steveai.agent.AgentManager;
import dev.steveai.agent.SteveAgent;
import dev.steveai.npc.CitizensNpcController;
import dev.steveai.storage.AgentStore;
import dev.steveai.task.TaskRunner;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class SurvivalNpcListener implements Listener {
    private final AgentManager agentManager;
    private final TaskRunner taskRunner;
    private final CitizensNpcController npcController;
    private final AgentStore agentStore;

    public SurvivalNpcListener(
        AgentManager agentManager,
        TaskRunner taskRunner,
        CitizensNpcController npcController,
        AgentStore agentStore
    ) {
        this.agentManager = agentManager;
        this.taskRunner = taskRunner;
        this.npcController = npcController;
        this.agentStore = agentStore;
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getEntity());
        if (npc == null) {
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity livingEntity)) {
            return;
        }

        agentManager.findByNpcId(npc.getId()).ifPresent(agent -> {
            if (agent.isWounded()) {
                event.setCancelled(true);
                return;
            }

            if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent entityEvent) {
                if (entityEvent.getDamager() instanceof LivingEntity damager) {
                    agent.setLastAttacker(damager);
                }
            }

            double finalDamage = event.getFinalDamage();
            double currentHealth = livingEntity.getHealth();
            if (currentHealth - finalDamage <= 0.0D) {
                event.setCancelled(true);
                livingEntity.setHealth(1.0D);
                agent.setWounded(true);
                npc.getNavigator().cancelNavigation();
                livingEntity.setGlowing(true);
                taskRunner.stopAgent(agent);
                agent.remember("Fell incapacitated at " + livingEntity.getLocation().getBlockX() + " " + livingEntity.getLocation().getBlockY() + " " + livingEntity.getLocation().getBlockZ());
                agentStore.saveAsync(agentManager);

                Player owner = Bukkit.getPlayer(agent.getOwnerId());
                if (owner != null) {
                    owner.sendMessage(ChatColor.RED + agent.getName() + " has been wounded! Feed them food (right-click) to revive them.");
                }
            } else {
                agent.remember("Took damage: " + finalDamage + " cause=" + event.getCause());
            }
        });
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getRightClicked());
        if (npc == null) {
            return;
        }

        Player player = event.getPlayer();

        agentManager.findByNpcId(npc.getId()).ifPresent(agent -> {
            if (!agent.isWounded()) {
                return;
            }

            if (!agent.getOwnerId().equals(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Only " + agent.getName() + "'s owner can revive them.");
                return;
            }

            ItemStack handItem = player.getInventory().getItemInMainHand();
            if (handItem.getType().isAir()) {
                return;
            }

            boolean isReviveItem = handItem.getType().isEdible()
                || handItem.getType() == Material.POTION
                || handItem.getType() == Material.SPLASH_POTION
                || handItem.getType() == Material.LINGERING_POTION;

            if (isReviveItem) {
                event.setCancelled(true);

                if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
                    handItem.setAmount(handItem.getAmount() - 1);
                }

                agent.setWounded(false);
                if (event.getRightClicked() instanceof LivingEntity livingEntity) {
                    livingEntity.setHealth(20.0D);
                    livingEntity.setGlowing(false);

                    Location loc = livingEntity.getLocation();
                    loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_BURP, 1.0F, 1.0F);
                    loc.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, loc.add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0.1);
                }

                player.sendMessage(ChatColor.GREEN + agent.getName() + " has been revived!");
                agent.remember("Revived by " + player.getName() + " using " + handItem.getType().name());
                agentStore.saveAsync(agentManager);
            }
        });
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getEntity());
        if (npc == null) {
            return;
        }
        agentManager.findByNpcId(npc.getId()).ifPresent(agent -> {
            Location deathLocation = event.getEntity().getLocation();
            agent.inventoryView().forEach((material, amount) -> {
                int remaining = amount;
                while (remaining > 0) {
                    int stackAmount = Math.min(remaining, material.getMaxStackSize());
                    deathLocation.getWorld().dropItemNaturally(deathLocation, new ItemStack(material, stackAmount));
                    remaining -= stackAmount;
                }
            });
            agent.clearInventory();
            agent.setDead(true);
            taskRunner.stopAgent(agent);
            Player owner = Bukkit.getPlayer(agent.getOwnerId());
            if (owner != null) {
                owner.sendMessage(ChatColor.RED + agent.getName() + " died and dropped its inventory.");
            }
            npcController.destroy(agent);
            agentManager.remove(agent);
            agentStore.save(agentManager);
        });
    }
}