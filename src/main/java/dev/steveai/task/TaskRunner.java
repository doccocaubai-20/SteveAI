package dev.steveai.task;

import dev.steveai.agent.AgentManager;
import dev.steveai.agent.SteveAgent;
import dev.steveai.npc.CitizensNpcController;
import dev.steveai.plan.Plan;
import dev.steveai.plan.PlanAction;
import dev.steveai.storage.AgentStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

public final class TaskRunner {
    private static final Set<Material> LOGS = Set.of(
        Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
        Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG,
        Material.CRIMSON_STEM, Material.WARPED_STEM
    );
    private static final Set<Material> ORES = Set.of(
        Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
        Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
        Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE, Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
        Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
        Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE
    );
    private static final List<Material> PICKAXES = List.of(Material.NETHERITE_PICKAXE, Material.DIAMOND_PICKAXE, Material.IRON_PICKAXE, Material.STONE_PICKAXE, Material.WOODEN_PICKAXE);
    private static final List<Material> AXES = List.of(Material.NETHERITE_AXE, Material.DIAMOND_AXE, Material.IRON_AXE, Material.STONE_AXE, Material.WOODEN_AXE);
    private static final List<Material> SHOVELS = List.of(Material.NETHERITE_SHOVEL, Material.DIAMOND_SHOVEL, Material.IRON_SHOVEL, Material.STONE_SHOVEL, Material.WOODEN_SHOVEL);
    private static final List<Material> SWORDS = List.of(Material.NETHERITE_SWORD, Material.DIAMOND_SWORD, Material.IRON_SWORD, Material.STONE_SWORD, Material.WOODEN_SWORD);

    private final JavaPlugin plugin;
    private final AgentManager agentManager;
    private final CitizensNpcController npcController;
    private final AgentStore agentStore;
    private final Map<String, ArrayDeque<PlanAction>> queues = new java.util.HashMap<>();
    private final Map<String, List<Location>> miningTargets = new java.util.HashMap<>();
    private final Map<String, Location> lastLocations = new java.util.HashMap<>();
    private final Map<String, Integer> stuckTicks = new java.util.HashMap<>();
    private final Map<String, Location> lastFollowTargets = new java.util.HashMap<>();
    private BukkitTask task;

    public TaskRunner(JavaPlugin plugin, AgentManager agentManager, CitizensNpcController npcController, AgentStore agentStore) {
        this.plugin = plugin;
        this.agentManager = agentManager;
        this.npcController = npcController;
        this.agentStore = agentStore;
    }

    public void start() {
        long period = Math.max(1L, plugin.getConfig().getLong("agent.follow-update-ticks", 5L));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        queues.clear();
    }

    public void enqueue(SteveAgent agent, Plan plan) {
        ArrayDeque<PlanAction> queue = queues.computeIfAbsent(agent.getName().toLowerCase(Locale.ROOT), ignored -> new ArrayDeque<>());
        queue.addAll(plan.actions());
        agent.setBusy(true);
    }

    public void stopAgent(SteveAgent agent) {
        String key = agent.getName().toLowerCase(Locale.ROOT);
        queues.remove(key);
        miningTargets.remove(key);
        lastLocations.remove(key);
        stuckTicks.remove(key);
        lastFollowTargets.remove(key);
        agent.setBusy(false);
        agent.setFollowing(false);
        agent.setProtecting(false);
    }

    private void tick() {
        for (SteveAgent agent : agentManager.all()) {
            if (agent.isDead() || agent.isWounded()) {
                continue;
            }
            npcController.syncLocation(agent);
            updateStuckReflexes(agent);

            // Phản xạ né Creeper
            if (updateFleeCreepers(agent)) {
                continue;
            }
            // Phản xạ tự ăn hồi máu
            updateAutoEating(agent);
            // Phản xạ tự vệ
            if (updateAutoDefense(agent)) {
                continue;
            }

            updateFollow(agent);
            updateProtect(agent);
            ArrayDeque<PlanAction> queue = queues.get(agent.getName().toLowerCase(Locale.ROOT));
            if (queue == null || queue.isEmpty()) {
                agent.setBusy(false);
                continue;
            }
            PlanAction action = queue.poll();
            execute(agent, action, queue);
            if (queue.isEmpty()) {
                agent.setBusy(false);
            }
        }
    }

    private void execute(SteveAgent agent, PlanAction action, ArrayDeque<PlanAction> queue) {
        Player owner = Bukkit.getPlayer(agent.getOwnerId());
        switch (action.type()) {
            case "say" -> say(agent, owner, action.text().isBlank() ? "I am ready." : action.text());
            case "wait" -> agent.remember("Waited for " + action.ticks() + " ticks");
            case "move_near_owner" -> moveNearOwner(agent, owner);
            case "follow_owner" -> followOwner(agent, owner);
            case "stop_following" -> stopFollowing(agent, owner);
            case "protect_owner" -> protectOwner(agent, owner);
            case "stop_protecting" -> stopProtecting(agent, owner);
            case "move_near_block" -> moveNearBlock(agent, owner, action);
            case "break_owner_target_block" -> breakOwnerTargetBlock(agent, owner, queue, action);
            case "break_nearest_block" -> breakNearestBlock(agent, owner, queue, action);
            case "collect_items" -> collectItems(agent, owner, queue, action);
            case "equip_best_tool" -> equipBestTool(agent, owner, action.material());
            case "place_block" -> placeBlock(agent, owner, queue, action);
            case "chop_tree" -> chopTree(agent, owner, queue, action);
            case "mine_vein" -> mineVein(agent, owner, queue, action);
            case "craft_item" -> craftItem(agent, owner, action);
            case "open_nearest_container" -> openNearestContainer(agent, owner, action);
            case "attack_nearest_hostile" -> attackNearestHostile(agent, owner, queue, action.radius());
            case "flee_to_owner" -> fleeToOwner(agent, owner);
            case "remember_fact" -> rememberFact(agent, owner, action.text());
            default -> plugin.getLogger().warning("Ignored unknown action: " + action.type());
        }
    }

    private void rememberFact(SteveAgent agent, Player owner, String fact) {
        if (fact == null || fact.isBlank()) {
            return;
        }
        agent.addLongTermMemory(fact);
        if (owner != null) {
            owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " remembered fact: " + fact);
        }
        agentStore.saveAsync(agentManager);
    }

    private void say(SteveAgent agent, Player owner, String text) {
        if (owner != null) {
            owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + ": " + text);
        }
        agent.remember("Said: " + text);
    }

    private void moveNearOwner(SteveAgent agent, Player owner) {
        if (owner == null) {
            agent.remember("Owner was offline, could not move.");
            return;
        }
        npcController.moveNearOwner(agent, owner);
        owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " is walking toward you.");
    }

    private void followOwner(SteveAgent agent, Player owner) {
        agent.setFollowing(true);
        if (owner != null) {
            owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " will follow you.");
        }
        updateFollow(agent);
    }

    private void stopFollowing(SteveAgent agent, Player owner) {
        agent.setFollowing(false);
        if (owner != null) {
            owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " stopped following.");
        }
    }

    private void protectOwner(SteveAgent agent, Player owner) {
        agent.setProtecting(true);
        if (owner != null) {
            owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " will protect you.");
        }
    }

    private void stopProtecting(SteveAgent agent, Player owner) {
        agent.setProtecting(false);
        if (owner != null) {
            owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " stopped protecting.");
        }
    }


    private void updateAutoPickup(SteveAgent agent) {
        if (!plugin.getConfig().getBoolean("agent.auto-pickup", true)) {
            return;
        }
        double radius = plugin.getConfig().getDouble("agent.auto-pickup-radius", 2.5D);
        Location center = npcController.currentLocation(agent).orElse(agent.getLocation());
        int collected = 0;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity instanceof Item item) {
                ItemStack stack = item.getItemStack();
                agent.addItem(stack.getType(), stack.getAmount());
                item.remove();
                collected += stack.getAmount();
            }
        }
        if (collected > 0) {
            agent.remember("Auto picked up " + collected + " item(s).");
        }
    }
    private void updateFollow(SteveAgent agent) {
        if (!agent.isFollowing()) {
            return;
        }
        Player owner = Bukkit.getPlayer(agent.getOwnerId());
        if (owner == null) {
            return;
        }
        Optional<Location> current = npcController.currentLocation(agent);
        if (current.isEmpty()) {
            return;
        }
        String key = agent.getName().toLowerCase(Locale.ROOT);
        Location lastTarget = lastFollowTargets.get(key);
        Location ownerLoc = owner.getLocation();

        double followDistance = plugin.getConfig().getDouble("agent.follow-distance", 4.0D);
        if (current.get().getWorld() != ownerLoc.getWorld()
            || current.get().distanceSquared(ownerLoc) > followDistance * followDistance) {

            // Only update Citizens target if owner has moved > 2.0 blocks from the last set target
            if (lastTarget == null || lastTarget.getWorld() != ownerLoc.getWorld()
                || lastTarget.distanceSquared(ownerLoc) > 2.0D * 2.0D) {
                npcController.moveNearOwner(agent, owner);
                lastFollowTargets.put(key, ownerLoc.clone());
            }
        }
    }

    private void updateProtect(SteveAgent agent) {
        if (!agent.isProtecting()) {
            return;
        }
        Player owner = Bukkit.getPlayer(agent.getOwnerId());
        if (owner == null) {
            return;
        }
        Optional<LivingEntity> hostile = nearestHostile(owner.getLocation(), plugin.getConfig().getInt("agent.protect-radius", 10));
        hostile.ifPresent(target -> attackEntity(agent, owner, target));
    }

    private boolean updateFleeCreepers(SteveAgent agent) {
        Location center = npcController.currentLocation(agent).orElse(agent.getLocation());
        org.bukkit.entity.Creeper dangerCreeper = null;
        double radius = 4.5D;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity instanceof org.bukkit.entity.Creeper creeper) {
                if (!creeper.isDead()) {
                    dangerCreeper = creeper;
                    break;
                }
            }
        }

        if (dangerCreeper != null) {
            Player owner = Bukkit.getPlayer(agent.getOwnerId());
            Location target = null;
            if (owner != null && owner.getWorld() == center.getWorld()) {
                target = owner.getLocation();
            } else {
                org.bukkit.util.Vector dir = center.toVector().subtract(dangerCreeper.getLocation().toVector()).normalize();
                target = center.clone().add(dir.multiply(5));
            }
            npcController.moveTo(agent, target);
            if (Math.random() < 0.1) {
                say(agent, owner, "Danger! Fleeing from Creeper!");
            }
            return true;
        }
        return false;
    }

    private void updateAutoEating(SteveAgent agent) {
        long now = System.currentTimeMillis();
        if (now < agent.getEatCooldownUntil()) {
            return;
        }
        Optional<Entity> entityOpt = npcController.currentEntity(agent);
        if (entityOpt.isEmpty() || !(entityOpt.get() instanceof LivingEntity livingEntity)) {
            return;
        }

        double health = livingEntity.getHealth();
        if (health >= 16.0D) {
            return;
        }

        Material foodMaterial = null;
        for (Map.Entry<Material, Integer> entry : agent.inventoryView().entrySet()) {
            if (entry.getKey().isEdible() && entry.getValue() > 0) {
                foodMaterial = entry.getKey();
                break;
            }
        }

        if (foodMaterial != null) {
            agent.removeItem(foodMaterial, 1);
            livingEntity.setHealth(Math.min(20.0D, health + 4.0D));
            Location loc = livingEntity.getLocation();
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_BURP, 1.0F, 1.0F);
            loc.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, loc.add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.1);

            Player owner = Bukkit.getPlayer(agent.getOwnerId());
            if (owner != null) {
                owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " ate " + foodMaterial.name() + " to heal. Health: " + String.format("%.1f", livingEntity.getHealth()) + "/20");
            }
            agent.remember("Automatically ate: " + foodMaterial.name());
            agent.setEatCooldownUntil(now + 5000L);
        }
    }

    private boolean updateAutoDefense(SteveAgent agent) {
        LivingEntity attacker = agent.getLastAttacker();
        if (attacker == null || attacker.isDead()) {
            agent.setLastAttacker(null);
            return false;
        }

        Optional<Location> npcLocation = npcController.currentLocation(agent);
        if (npcLocation.isEmpty() || npcLocation.get().getWorld() != attacker.getWorld()
            || npcLocation.get().distanceSquared(attacker.getLocation()) > 12.0D * 12.0D) {
            agent.setLastAttacker(null);
            return false;
        }

        Player owner = Bukkit.getPlayer(agent.getOwnerId());
        attackEntity(agent, owner, attacker);
        return true;
    }

    private void updateStuckReflexes(SteveAgent agent) {
        Optional<net.citizensnpcs.api.npc.NPC> npcOpt = npcController.find(agent);
        if (npcOpt.isEmpty()) {
            return;
        }
        net.citizensnpcs.api.npc.NPC npc = npcOpt.get();
        if (npc.isSpawned() && npc.getNavigator().isNavigating()) {
            Entity entity = npc.getEntity();
            Location currentLoc = entity.getLocation();
            String key = agent.getName().toLowerCase(Locale.ROOT);
            Location lastLoc = lastLocations.get(key);
            int ticks = stuckTicks.getOrDefault(key, 0);

            if (lastLoc != null && lastLoc.getWorld() == currentLoc.getWorld()) {
                double dist = currentLoc.distance(lastLoc);
                if (dist < 0.05D) {
                    ticks++;
                } else {
                    ticks = 0;
                }
            }
            lastLocations.put(key, currentLoc);
            stuckTicks.put(key, ticks);

            if (ticks >= 3) {
                Player owner = Bukkit.getPlayer(agent.getOwnerId());

                if (ticks >= 15) {
                    if (owner != null && owner.getWorld() == currentLoc.getWorld()) {
                        npcController.teleportTo(agent, owner.getLocation().add(1.0, 0, 1.0));
                        say(agent, owner, "I got stuck! Teleported to you.");
                    }
                    stuckTicks.put(key, 0);
                } else {
                    if (entity instanceof LivingEntity livingEntity) {
                        livingEntity.setVelocity(livingEntity.getVelocity().add(new org.bukkit.util.Vector(0, 0.42D, 0)));
                    }

                    org.bukkit.util.Vector dir = currentLoc.getDirection().normalize();
                    Location frontLoc1 = currentLoc.clone().add(dir);
                    Location frontLoc2 = currentLoc.clone().add(0, 1, 0).add(dir);

                    tryDig(agent, frontLoc1.getBlock());
                    tryDig(agent, frontLoc2.getBlock());
                }
            }
        } else {
            stuckTicks.put(agent.getName().toLowerCase(Locale.ROOT), 0);
        }
    }

    private void tryDig(SteveAgent agent, Block block) {
        if (block == null || block.getType().isAir() || !block.getType().isSolid()) {
            return;
        }
        Material type = block.getType();
        if (type == Material.BEDROCK || type == Material.OBSIDIAN || type == Material.BARRIER
            || type == Material.CHEST || type == Material.FURNACE || type == Material.CRAFTING_TABLE
            || type.name().contains("COMMAND") || type.name().contains("PORTAL")) {
            return;
        }
        Material tool = chooseTool(agent, type);
        npcController.equipMainHand(agent, tool);

        for (ItemStack drop : block.getDrops(tool.isAir() ? new ItemStack(Material.AIR) : new ItemStack(tool))) {
            agent.addItem(drop.getType(), drop.getAmount());
        }
        block.setType(Material.AIR);
        block.getWorld().playEffect(block.getLocation(), org.bukkit.Effect.STEP_SOUND, type);
        agent.remember("Cleared obstacle by digging: " + type.name());
    }

    private void moveNearBlock(SteveAgent agent, Player owner, PlanAction action) {
        if (owner == null) {
            return;
        }
        Material material = matchMaterial(action.material());
        if (material == null) {
            say(agent, owner, "I don't know material " + action.material() + ".");
            return;
        }
        Optional<Block> block = findNearestBlock(owner.getLocation(), material, action.radius());
        if (block.isEmpty()) {
            say(agent, owner, "I couldn't find nearby " + material.name() + ".");
            return;
        }
        npcController.moveTo(agent, approachLocation(block.get()));
        owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " is walking to nearby " + material.name() + ".");
    }

    private void breakOwnerTargetBlock(SteveAgent agent, Player owner, ArrayDeque<PlanAction> queue, PlanAction action) {
        if (owner == null) {
            return;
        }
        Block block = owner.getTargetBlockExact(8);
        if (block == null || block.getType().isAir()) {
            say(agent, owner, "I don't see a target block.");
            return;
        }
        breakBlockByHand(agent, owner, queue, action, block);
    }

    private void breakNearestBlock(SteveAgent agent, Player owner, ArrayDeque<PlanAction> queue, PlanAction action) {
        if (owner == null) {
            return;
        }
        Material material = matchMaterial(action.material());
        if (material == null) {
            say(agent, owner, "I don't know material " + action.material() + ".");
            return;
        }
        Optional<Block> block = findNearestBlock(owner.getLocation(), material, action.radius());
        if (block.isEmpty()) {
            say(agent, owner, "I couldn't find nearby " + material.name() + ".");
            return;
        }
        breakBlockByHand(agent, owner, queue, action, block.get());
    }

    private void breakBlockByHand(SteveAgent agent, Player owner, ArrayDeque<PlanAction> queue, PlanAction action, Block block) {
        if (!withinOwnerDistance(owner, block.getLocation())) {
            say(agent, owner, "That block is too far away.");
            return;
        }
        Optional<Location> npcLocation = npcController.currentLocation(agent);
        double reach = plugin.getConfig().getDouble("agent.action-reach", 4.5D);
        if (npcLocation.isEmpty() || npcLocation.get().getWorld() != block.getWorld()
            || npcLocation.get().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5)) > reach * reach) {
            npcController.moveTo(agent, approachLocation(block));
            queue.addFirst(action);
            owner.sendMessage(ChatColor.YELLOW + agent.getName() + " is moving close enough to break " + block.getType().name() + ".");
            return;
        }
        Material tool = chooseTool(agent, block.getType());
        npcController.equipMainHand(agent, tool);
        Material material = block.getType();
        for (ItemStack drop : block.getDrops(tool.isAir() ? new ItemStack(Material.AIR) : new ItemStack(tool))) {
            agent.addItem(drop.getType(), drop.getAmount());
        }
        block.setType(Material.AIR);
        owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " broke " + material.name() + " and stored drops.");
        agent.remember("Broke block by hand: " + material.name());
    }

    private void collectItems(SteveAgent agent, Player owner, ArrayDeque<PlanAction> queue, PlanAction action) {
        Location center = npcController.currentLocation(agent).orElse(owner == null ? agent.getLocation() : owner.getLocation());
        int radius = Math.max(1, Math.min(action.radius(), 12));

        Item nearestItem = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity instanceof Item item) {
                if (item.isDead() || !item.isValid()) {
                    continue;
                }
                if (owner != null && !withinOwnerDistance(owner, item.getLocation())) {
                    continue;
                }
                double distSq = item.getLocation().distanceSquared(center);
                if (distSq < nearestDistSq) {
                    nearestItem = item;
                    nearestDistSq = distSq;
                }
            }
        }

        if (nearestItem == null) {
            if (owner != null) {
                owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " finished collecting nearby items.");
            }
            return;
        }

        if (nearestDistSq > 1.5D * 1.5D) {
            npcController.moveTo(agent, nearestItem.getLocation());
            queue.addFirst(action);
        } else {
            ItemStack stack = nearestItem.getItemStack();
            agent.addItem(stack.getType(), stack.getAmount());
            nearestItem.remove();

            Location loc = nearestItem.getLocation();
            loc.getWorld().playSound(loc, Sound.ENTITY_ITEM_PICKUP, 0.5F, 1.5F);

            if (owner != null) {
                owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " picked up " + stack.getType().name() + " x" + stack.getAmount());
            }

            queue.addFirst(action);
        }
    }

    private void equipBestTool(SteveAgent agent, Player owner, String materialName) {
        Material target = matchMaterial(materialName);
        Material tool = chooseTool(agent, target == null ? Material.STONE : target);
        npcController.equipMainHand(agent, tool);
        if (owner != null) {
            owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " equipped " + tool.name() + ".");
        }
    }

    private void placeBlock(SteveAgent agent, Player owner, ArrayDeque<PlanAction> queue, PlanAction action) {
        if (owner == null) {
            return;
        }
        Material material = matchMaterial(action.material());
        if (material == null || !material.isBlock()) {
            say(agent, owner, "I can't place " + action.material() + ".");
            return;
        }
        if (agent.count(material) <= 0) {
            say(agent, owner, "I don't have " + material.name() + " in my inventory.");
            return;
        }
        Block target = owner.getTargetBlockExact(8);
        if (target == null) {
            say(agent, owner, "I don't see where to place it.");
            return;
        }
        Block place = target.getRelative(BlockFace.UP);
        if (!place.getType().isAir()) {
            say(agent, owner, "There is no space above the target block.");
            return;
        }
        if (!withinReachOrMove(agent, owner, queue, action, place)) {
            return;
        }
        place.setType(material);
        agent.removeItem(material, 1);
        owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " placed " + material.name() + ".");
    }

    private void chopTree(SteveAgent agent, Player owner, ArrayDeque<PlanAction> queue, PlanAction action) {
        if (owner == null) {
            return;
        }
        String agentKey = agent.getName().toLowerCase(Locale.ROOT);
        List<Location> targets = miningTargets.get(agentKey);
        if (targets == null) {
            Optional<Block> start = findNearestAnyBlock(owner.getLocation(), LOGS, action.radius());
            if (start.isEmpty()) {
                say(agent, owner, "I couldn't find a nearby tree log.");
                return;
            }
            List<Block> logs = connectedBlocks(start.get(), LOGS, plugin.getConfig().getInt("agent.max-tree-blocks", 64));
            targets = new ArrayList<>();
            for (Block log : logs) {
                targets.add(log.getLocation());
            }
            miningTargets.put(agentKey, targets);
            owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " identified a tree with " + targets.size() + " logs. Starting to chop...");
        }

        Block nextBlock = null;
        while (!targets.isEmpty()) {
            Location loc = targets.get(0);
            Block block = loc.getBlock();
            if (LOGS.contains(block.getType())) {
                nextBlock = block;
                break;
            } else {
                targets.remove(0);
            }
        }

        if (nextBlock == null) {
            miningTargets.remove(agentKey);
            owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " finished chopping the tree. Inventory: " + agent.inventorySummary());
            return;
        }

        if (!withinOwnerDistance(owner, nextBlock.getLocation())) {
            targets.remove(0);
            queue.addFirst(action);
            return;
        }

        Optional<Location> npcLocation = npcController.currentLocation(agent);
        double reach = plugin.getConfig().getDouble("agent.action-reach", 4.5D);
        if (npcLocation.isEmpty() || npcLocation.get().getWorld() != nextBlock.getWorld()
            || npcLocation.get().distanceSquared(nextBlock.getLocation().add(0.5, 0.5, 0.5)) > reach * reach) {
            npcController.moveTo(agent, approachLocation(nextBlock));
            queue.addFirst(action);
            return;
        }

        Material axe = chooseBestFrom(agent, AXES).orElse(Material.AIR);
        npcController.equipMainHand(agent, axe);
        Material material = nextBlock.getType();
        for (ItemStack drop : nextBlock.getDrops(axe.isAir() ? new ItemStack(Material.AIR) : new ItemStack(axe))) {
            agent.addItem(drop.getType(), drop.getAmount());
        }
        nextBlock.setType(Material.AIR);
        agent.remember("Chopped log: " + material.name());
        targets.remove(0);

        queue.addFirst(action);
    }

    private void mineVein(SteveAgent agent, Player owner, ArrayDeque<PlanAction> queue, PlanAction action) {
        if (owner == null) {
            return;
        }
        Material wanted = matchMaterial(action.material());
        Set<Material> targetMaterials = wanted == null ? ORES : Set.of(wanted);

        String agentKey = agent.getName().toLowerCase(Locale.ROOT);
        List<Location> targets = miningTargets.get(agentKey);
        if (targets == null) {
            Optional<Block> start = findNearestAnyBlock(owner.getLocation(), targetMaterials, action.radius());
            if (start.isEmpty()) {
                say(agent, owner, "I couldn't find that vein nearby.");
                return;
            }
            List<Block> vein = connectedBlocks(start.get(), targetMaterials, plugin.getConfig().getInt("agent.max-vein-blocks", 32));
            targets = new ArrayList<>();
            for (Block ore : vein) {
                targets.add(ore.getLocation());
            }
            miningTargets.put(agentKey, targets);
            owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " identified a vein with " + targets.size() + " blocks. Starting to mine...");
        }

        Block nextBlock = null;
        while (!targets.isEmpty()) {
            Location loc = targets.get(0);
            Block block = loc.getBlock();
            if (targetMaterials.contains(block.getType())) {
                nextBlock = block;
                break;
            } else {
                targets.remove(0);
            }
        }

        if (nextBlock == null) {
            miningTargets.remove(agentKey);
            owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " finished mining the vein. Inventory: " + agent.inventorySummary());
            return;
        }

        if (!withinOwnerDistance(owner, nextBlock.getLocation())) {
            targets.remove(0);
            queue.addFirst(action);
            return;
        }

        Optional<Location> npcLocation = npcController.currentLocation(agent);
        double reach = plugin.getConfig().getDouble("agent.action-reach", 4.5D);
        if (npcLocation.isEmpty() || npcLocation.get().getWorld() != nextBlock.getWorld()
            || npcLocation.get().distanceSquared(nextBlock.getLocation().add(0.5, 0.5, 0.5)) > reach * reach) {
            npcController.moveTo(agent, approachLocation(nextBlock));
            queue.addFirst(action);
            return;
        }

        Material pickaxe = chooseBestFrom(agent, PICKAXES).orElse(Material.AIR);
        npcController.equipMainHand(agent, pickaxe);
        Material material = nextBlock.getType();
        for (ItemStack drop : nextBlock.getDrops(pickaxe.isAir() ? new ItemStack(Material.AIR) : new ItemStack(pickaxe))) {
            agent.addItem(drop.getType(), drop.getAmount());
        }
        nextBlock.setType(Material.AIR);
        agent.remember("Mined ore: " + material.name());
        targets.remove(0);

        queue.addFirst(action);
    }

    private void craftItem(SteveAgent agent, Player owner, PlanAction action) {
        Material target = matchMaterial(action.material());
        if (target == null) {
            say(agent, owner, "I don't know how to craft " + action.material() + ".");
            return;
        }
        int crafted = 0;
        int amount = action.amount();
        for (int i = 0; i < amount; i++) {
            if (craftOne(agent, target)) {
                crafted++;
            } else {
                break;
            }
        }
        if (owner != null) {
            owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " crafted " + crafted + " x " + target.name() + ". Inventory: " + agent.inventorySummary());
        }
    }

    private boolean craftOne(SteveAgent agent, Material target) {
        if (target == Material.STICK && removeAny(agent, planks(), 2)) {
            agent.addItem(Material.STICK, 4);
            return true;
        }
        if (target == Material.CRAFTING_TABLE && removeAny(agent, planks(), 4)) {
            agent.addItem(Material.CRAFTING_TABLE, 1);
            return true;
        }
        if (target.name().endsWith("_PLANKS")) {
            Material log = matchingLog(target);
            if (log != null && agent.removeItem(log, 1)) {
                agent.addItem(target, 4);
                return true;
            }
        }
        if (target == Material.WOODEN_PICKAXE && removeAny(agent, planks(), 3) && agent.removeItem(Material.STICK, 2)) {
            agent.addItem(Material.WOODEN_PICKAXE, 1);
            return true;
        }
        if (target == Material.WOODEN_AXE && removeAny(agent, planks(), 3) && agent.removeItem(Material.STICK, 2)) {
            agent.addItem(Material.WOODEN_AXE, 1);
            return true;
        }
        if (target == Material.STONE_PICKAXE && agent.removeItem(Material.COBBLESTONE, 3) && agent.removeItem(Material.STICK, 2)) {
            agent.addItem(Material.STONE_PICKAXE, 1);
            return true;
        }
        if (target == Material.STONE_AXE && agent.removeItem(Material.COBBLESTONE, 3) && agent.removeItem(Material.STICK, 2)) {
            agent.addItem(Material.STONE_AXE, 1);
            return true;
        }
        if (target == Material.TORCH && agent.removeItem(Material.STICK, 1) && (agent.removeItem(Material.COAL, 1) || agent.removeItem(Material.CHARCOAL, 1))) {
            agent.addItem(Material.TORCH, 4);
            return true;
        }
        return false;
    }

    private void openNearestContainer(SteveAgent agent, Player owner, PlanAction action) {
        if (owner == null) {
            return;
        }
        Material material = matchMaterial(action.material());
        if (material == null) {
            material = Material.CHEST;
        }
        Optional<Block> block = findNearestBlock(owner.getLocation(), material, action.radius());
        if (block.isEmpty()) {
            say(agent, owner, "I couldn't find nearby " + material.name() + ".");
            return;
        }
        npcController.moveTo(agent, approachLocation(block.get()));
        if (material == Material.CRAFTING_TABLE) {
            owner.openWorkbench(block.get().getLocation(), true);
            return;
        }
        BlockState state = block.get().getState();
        if (state instanceof InventoryHolder holder) {
            owner.openInventory(holder.getInventory());
            owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " opened nearby " + material.name() + " for you.");
        } else {
            say(agent, owner, material.name() + " does not expose an inventory here.");
        }
    }

    private void attackNearestHostile(SteveAgent agent, Player owner, ArrayDeque<PlanAction> queue, int radius) {
        Location center = npcController.currentLocation(agent).orElse(owner == null ? agent.getLocation() : owner.getLocation());
        Optional<LivingEntity> hostile = nearestHostile(center, radius);
        if (hostile.isEmpty()) {
            say(agent, owner, "I don't see a hostile mob nearby.");
            return;
        }
        attackEntity(agent, owner, hostile.get());
    }

    private void attackEntity(SteveAgent agent, Player owner, LivingEntity target) {
        long now = System.currentTimeMillis();
        if (now < agent.getAttackCooldownUntil()) {
            return;
        }
        Optional<Location> npcLocation = npcController.currentLocation(agent);
        if (npcLocation.isEmpty() || npcLocation.get().distanceSquared(target.getLocation()) > 3.5D * 3.5D) {
            npcController.moveTo(agent, target.getLocation());
            return;
        }
        Material weapon = chooseBestFrom(agent, SWORDS).orElse(chooseBestFrom(agent, AXES).orElse(Material.AIR));
        npcController.equipMainHand(agent, weapon);
        Entity damager = npcController.currentEntity(agent).orElse(owner);
        target.damage(weaponDamage(weapon), damager);
        int cooldownTicks = plugin.getConfig().getInt("agent.attack-cooldown-ticks", 20);
        agent.setAttackCooldownUntil(now + cooldownTicks * 50L);
        if (owner != null) {
            owner.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY + " attacked " + target.getType().name() + ".");
        }
    }

    private void fleeToOwner(SteveAgent agent, Player owner) {
        if (owner == null) {
            return;
        }
        npcController.moveNearOwner(agent, owner);
        owner.sendMessage(ChatColor.YELLOW + agent.getName() + " is fleeing back to you.");
    }

    private Location approachLocation(Block block) {
        return block.getLocation().add(1.5, 0, 1.5);
    }

    private boolean withinReachOrMove(SteveAgent agent, Player owner, ArrayDeque<PlanAction> queue, PlanAction action, Block block) {
        Optional<Location> npcLocation = npcController.currentLocation(agent);
        double reach = plugin.getConfig().getDouble("agent.action-reach", 4.5D);
        if (npcLocation.isEmpty() || npcLocation.get().getWorld() != block.getWorld()
            || npcLocation.get().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5)) > reach * reach) {
            npcController.moveTo(agent, approachLocation(block));
            queue.addFirst(action);
            owner.sendMessage(ChatColor.YELLOW + agent.getName() + " is moving close enough.");
            return false;
        }
        return true;
    }

    private Optional<Block> findNearestBlock(Location center, Material material, int radius) {
        return findNearestAnyBlock(center, Set.of(material), radius);
    }

    private Optional<Block> findNearestAnyBlock(Location center, Set<Material> materials, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return Optional.empty();
        }
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        Block nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        double maxDistSq = (double) radius * radius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double distSq = (double) dx * dx + dy * dy + dz * dz;
                    if (distSq > maxDistSq) {
                        continue;
                    }
                    if (distSq >= nearestDistSq) {
                        continue;
                    }
                    int chunkX = (cx + dx) >> 4;
                    int chunkZ = (cz + dz) >> 4;
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (materials.contains(block.getType())) {
                        nearest = block;
                        nearestDistSq = distSq;
                    }
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    private List<Block> connectedBlocks(Block start, Set<Material> materials, int limit) {
        List<Block> result = new ArrayList<>();
        Queue<Block> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty() && result.size() < limit) {
            Block block = queue.poll();
            String key = block.getX() + ":" + block.getY() + ":" + block.getZ();
            if (!seen.add(key) || !materials.contains(block.getType())) {
                continue;
            }
            result.add(block);
            for (BlockFace face : List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                queue.add(block.getRelative(face));
            }
        }
        return result;
    }

    private Optional<LivingEntity> nearestHostile(Location center, int radius) {
        return center.getWorld().getNearbyEntities(center, radius, radius, radius).stream()
            .filter(entity -> entity instanceof Monster)
            .map(entity -> (LivingEntity) entity)
            .filter(entity -> !entity.isDead())
            .min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(center)));
    }

    private Material chooseTool(SteveAgent agent, Material target) {
        if (target == null) {
            return Material.AIR;
        }
        if (LOGS.contains(target)) {
            return chooseBestFrom(agent, AXES).orElse(Material.AIR);
        }
        if (ORES.contains(target) || target.name().contains("STONE") || target.name().contains("ORE")) {
            return chooseBestFrom(agent, PICKAXES).orElse(Material.AIR);
        }
        if (target.name().contains("DIRT") || target.name().contains("SAND") || target.name().contains("GRAVEL")) {
            return chooseBestFrom(agent, SHOVELS).orElse(Material.AIR);
        }
        return Material.AIR;
    }

    private Optional<Material> chooseBestFrom(SteveAgent agent, List<Material> candidates) {
        return candidates.stream().filter(material -> agent.count(material) > 0).findFirst();
    }

    private Material matchMaterial(String materialName) {
        if (materialName == null || materialName.isBlank()) {
            return null;
        }
        return Material.matchMaterial(materialName.trim().toUpperCase(Locale.ROOT));
    }

    private boolean withinOwnerDistance(Player owner, Location location) {
        if (owner.getWorld() != location.getWorld()) {
            return false;
        }
        int maxDistance = plugin.getConfig().getInt("agent.max-distance-from-owner", 64);
        return owner.getLocation().distanceSquared(location) <= maxDistance * maxDistance;
    }

    private boolean removeAny(SteveAgent agent, Set<Material> materials, int amount) {
        for (Material material : materials) {
            if (agent.count(material) >= amount) {
                return agent.removeItem(material, amount);
            }
        }
        return false;
    }

    private Set<Material> planks() {
        return Set.of(Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS, Material.JUNGLE_PLANKS,
            Material.ACACIA_PLANKS, Material.DARK_OAK_PLANKS, Material.MANGROVE_PLANKS, Material.CHERRY_PLANKS,
            Material.CRIMSON_PLANKS, Material.WARPED_PLANKS);
    }

    private Material matchingLog(Material planks) {
        return switch (planks) {
            case OAK_PLANKS -> Material.OAK_LOG;
            case SPRUCE_PLANKS -> Material.SPRUCE_LOG;
            case BIRCH_PLANKS -> Material.BIRCH_LOG;
            case JUNGLE_PLANKS -> Material.JUNGLE_LOG;
            case ACACIA_PLANKS -> Material.ACACIA_LOG;
            case DARK_OAK_PLANKS -> Material.DARK_OAK_LOG;
            case MANGROVE_PLANKS -> Material.MANGROVE_LOG;
            case CHERRY_PLANKS -> Material.CHERRY_LOG;
            case CRIMSON_PLANKS -> Material.CRIMSON_STEM;
            case WARPED_PLANKS -> Material.WARPED_STEM;
            default -> null;
        };
    }

    private double weaponDamage(Material weapon) {
        if (weapon == Material.NETHERITE_SWORD || weapon == Material.DIAMOND_SWORD) return 7.0D;
        if (weapon == Material.IRON_SWORD) return 6.0D;
        if (weapon == Material.STONE_SWORD) return 5.0D;
        if (weapon == Material.WOODEN_SWORD) return 4.0D;
        if (weapon.name().endsWith("_AXE")) return 5.0D;
        return 2.0D;
    }
}