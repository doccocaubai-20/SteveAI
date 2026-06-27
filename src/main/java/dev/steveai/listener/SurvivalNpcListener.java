package dev.steveai.listener;

import dev.steveai.agent.AgentManager;
import dev.steveai.agent.SteveAgent;
import dev.steveai.npc.CitizensNpcController;
import dev.steveai.storage.AgentStore;
import dev.steveai.task.TaskRunner;
import dev.steveai.plan.Plan;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SurvivalNpcListener implements Listener {
    private final AgentManager agentManager;
    private final TaskRunner taskRunner;
    private final CitizensNpcController npcController;
    private final Map<UUID, String> openMenus = new HashMap<>();
    private final Map<UUID, String> openInventories = new HashMap<>();
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
                    if (!damager.getUniqueId().equals(agent.getOwnerId())) {
                        agent.setLastAttacker(damager);
                    }
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
                if (agent.getOwnerId().equals(player.getUniqueId())) {
                    event.setCancelled(true);
                    Bukkit.getScheduler().runTask(dev.steveai.StevePaperAIPlugin.getPlugin(dev.steveai.StevePaperAIPlugin.class), () -> {
                        openControlMenu(player, agent);
                    });
                }
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
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!openMenus.containsKey(uuid)) {
            return;
        }

        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 9) {
            return;
        }

        String name = openMenus.get(uuid);
        agentManager.find(name).ifPresent(agent -> {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0F, 1.0F);
            switch (slot) {
                case 0 -> {
                    agent.setFollowing(!agent.isFollowing());
                    if (agent.isFollowing()) {
                        agent.setSitting(false);
                    }
                    agentStore.saveAsync(agentManager);
                    openControlMenu(player, agent);
                }
                case 1 -> {
                    agent.setProtecting(!agent.isProtecting());
                    agentStore.saveAsync(agentManager);
                    openControlMenu(player, agent);
                }
                case 2 -> {
                    agent.setSitting(!agent.isSitting());
                    if (agent.isSitting()) {
                        taskRunner.stopAgent(agent);
                        agent.setSitting(true);
                    }
                    agentStore.saveAsync(agentManager);
                    openControlMenu(player, agent);
                }
                case 4 -> {
                    openMenus.remove(uuid);
                    openAgentInventory(player, agent);
                }
                case 6 -> {
                    player.closeInventory();
                    Plan plan = new Plan(List.of(new dev.steveai.plan.PlanAction("collect_items", "", "", "", 20, 8, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
                    taskRunner.enqueue(agent, plan);
                    agent.setSitting(false);
                    agentStore.saveAsync(agentManager);
                    player.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " is going to collect items.");
                }
                case 7 -> {
                    player.closeInventory();
                    Plan plan = new Plan(List.of(new dev.steveai.plan.PlanAction("chop_tree", "", "", "", 20, 12, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
                    taskRunner.enqueue(agent, plan);
                    agent.setSitting(false);
                    agentStore.saveAsync(agentManager);
                    player.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " is going to chop a tree.");
                }
                case 8 -> {
                    player.closeInventory();
                    Plan plan = new Plan(List.of(new dev.steveai.plan.PlanAction("mine_vein", "", "", "", 20, 12, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
                    taskRunner.enqueue(agent, plan);
                    agent.setSitting(false);
                    agentStore.saveAsync(agentManager);
                    player.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " is going to mine ores.");
                }
            }
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        openMenus.remove(uuid);

        if (openInventories.containsKey(uuid)) {
            String name = openInventories.remove(uuid);
            agentManager.find(name).ifPresent(agent -> {
                agent.clearInventory();
                Inventory inv = event.getInventory();
                for (int i = 0; i < 27; i++) {
                    ItemStack item = inv.getItem(i);
                    if (item != null && !item.getType().isAir()) {
                        agent.addItem(item.getType(), item.getAmount());
                    }
                }
                agentStore.saveAsync(agentManager);
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_CLOSE, 1.0F, 1.0F);
                player.sendMessage(ChatColor.GREEN + agent.getName() + "'s inventory has been updated.");
            });
        }
    }

    private void openControlMenu(Player player, SteveAgent agent) {
        Inventory gui = Bukkit.createInventory(player, 9, ChatColor.GREEN + agent.getName() + "'s Control Menu");

        ItemStack followItem = new ItemStack(agent.isFollowing() ? Material.LIME_WOOL : Material.RED_WOOL);
        ItemMeta followMeta = followItem.getItemMeta();
        if (followMeta != null) {
            followMeta.setDisplayName(ChatColor.YELLOW + "Follow Owner: " + (agent.isFollowing() ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
            followMeta.setLore(List.of(ChatColor.GRAY + "Click to toggle follow mode."));
            followItem.setItemMeta(followMeta);
        }
        gui.setItem(0, followItem);

        ItemStack protectItem = new ItemStack(agent.isProtecting() ? Material.SHIELD : Material.LEATHER_CHESTPLATE);
        ItemMeta protectMeta = protectItem.getItemMeta();
        if (protectMeta != null) {
            protectMeta.setDisplayName(ChatColor.YELLOW + "Protect Owner: " + (agent.isProtecting() ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
            protectMeta.setLore(List.of(ChatColor.GRAY + "Click to toggle protect mode."));
            protectItem.setItemMeta(protectMeta);
        }
        gui.setItem(1, protectItem);

        ItemStack sitItem = new ItemStack(agent.isSitting() ? Material.OAK_STAIRS : Material.OAK_PRESSURE_PLATE);
        ItemMeta sitMeta = sitItem.getItemMeta();
        if (sitMeta != null) {
            sitMeta.setDisplayName(ChatColor.YELLOW + "State: " + (agent.isSitting() ? ChatColor.GOLD + "SITTING" : ChatColor.GREEN + "READY"));
            sitMeta.setLore(List.of(ChatColor.GRAY + "Click to toggle sitting/standing."));
            sitItem.setItemMeta(sitMeta);
        }
        gui.setItem(2, sitItem);

        ItemStack invItem = new ItemStack(Material.CHEST);
        ItemMeta invMeta = invItem.getItemMeta();
        if (invMeta != null) {
            invMeta.setDisplayName(ChatColor.AQUA + "Open Hòm Đồ");
            invMeta.setLore(List.of(ChatColor.GRAY + "Click to open and manage Bob's items."));
            invItem.setItemMeta(invMeta);
        }
        gui.setItem(4, invItem);

        ItemStack collectItem = new ItemStack(Material.HOPPER);
        ItemMeta collectMeta = collectItem.getItemMeta();
        if (collectMeta != null) {
            collectMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "Collect Items");
            collectMeta.setLore(List.of(ChatColor.GRAY + "Click to make Bob collect nearby items."));
            collectItem.setItemMeta(collectMeta);
        }
        gui.setItem(6, collectItem);

        ItemStack axeItem = new ItemStack(Material.IRON_AXE);
        ItemMeta axeMeta = axeItem.getItemMeta();
        if (axeMeta != null) {
            axeMeta.setDisplayName(ChatColor.GREEN + "Chop Tree");
            axeMeta.setLore(List.of(ChatColor.GRAY + "Click to make Bob chop a nearby tree."));
            axeItem.setItemMeta(axeMeta);
        }
        gui.setItem(7, axeItem);

        ItemStack pickaxeItem = new ItemStack(Material.IRON_PICKAXE);
        ItemMeta pickaxeMeta = pickaxeItem.getItemMeta();
        if (pickaxeMeta != null) {
            pickaxeMeta.setDisplayName(ChatColor.GOLD + "Mine Ores");
            pickaxeMeta.setLore(List.of(ChatColor.GRAY + "Click to make Bob mine nearby ores."));
            pickaxeItem.setItemMeta(pickaxeMeta);
        }
        gui.setItem(8, pickaxeItem);

        openMenus.put(player.getUniqueId(), agent.getName());
        player.openInventory(gui);
    }

    private void openAgentInventory(Player player, SteveAgent agent) {
        Inventory chest = Bukkit.createInventory(player, 27, ChatColor.DARK_GREEN + agent.getName() + "'s Inventory");

        int slot = 0;
        for (Map.Entry<Material, Integer> entry : agent.inventoryView().entrySet()) {
            if (slot >= 27) {
                break;
            }
            Material mat = entry.getKey();
            int amount = entry.getValue();
            while (amount > 0 && slot < 27) {
                int stackAmount = Math.min(amount, mat.getMaxStackSize());
                chest.setItem(slot++, new ItemStack(mat, stackAmount));
                amount -= stackAmount;
            }
        }

        openInventories.put(player.getUniqueId(), agent.getName());
        player.openInventory(chest);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0F, 1.0F);
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