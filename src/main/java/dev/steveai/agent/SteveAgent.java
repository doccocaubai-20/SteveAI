package dev.steveai.agent;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

public final class SteveAgent {
    private final String name;
    private final UUID ownerId;
    private Location location;
    private final Deque<String> memory = new ArrayDeque<>();
    private final List<String> longTermMemory = new ArrayList<>();
    private final Map<Material, Integer> inventory = new EnumMap<>(Material.class);
    private boolean busy;
    private boolean following;
    private boolean protecting;
    private boolean dead;
    private boolean wounded;
    private boolean sitting;
    private long attackCooldownUntil;
    private long eatCooldownUntil;
    private transient LivingEntity lastAttacker;
    private Integer citizensNpcId;
    private Material equippedItem = Material.AIR;

    public SteveAgent(String name, Player owner) {
        this(name, owner.getUniqueId(), owner.getLocation().clone());
        remember("Created near " + owner.getName() + " at " + Instant.now());
    }

    public SteveAgent(String name, UUID ownerId, Location location) {
        this.name = name;
        this.ownerId = ownerId;
        this.location = location.clone();
    }

    public String getName() {
        return name;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public Location getLocation() {
        return location.clone();
    }

    public void setLocation(Location location) {
        this.location = location.clone();
    }

    public OptionalInt getCitizensNpcId() {
        return citizensNpcId == null ? OptionalInt.empty() : OptionalInt.of(citizensNpcId);
    }

    public void setCitizensNpcId(int citizensNpcId) {
        this.citizensNpcId = citizensNpcId;
    }

    public boolean isBusy() {
        return busy;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
    }

    public boolean isFollowing() {
        return following;
    }

    public void setFollowing(boolean following) {
        this.following = following;
    }

    public boolean isProtecting() {
        return protecting;
    }

    public void setProtecting(boolean protecting) {
        this.protecting = protecting;
    }

    public boolean isSitting() {
        return sitting;
    }

    public void setSitting(boolean sitting) {
        this.sitting = sitting;
    }

    public long getAttackCooldownUntil() {
        return attackCooldownUntil;
    }

    public void setAttackCooldownUntil(long attackCooldownUntil) {
        this.attackCooldownUntil = attackCooldownUntil;
    }

    public long getEatCooldownUntil() {
        return eatCooldownUntil;
    }

    public void setEatCooldownUntil(long eatCooldownUntil) {
        this.eatCooldownUntil = eatCooldownUntil;
    }

    public boolean isDead() {
        return dead;
    }

    public void setDead(boolean dead) {
        this.dead = dead;
    }

    public boolean isWounded() {
        return wounded;
    }

    public void setWounded(boolean wounded) {
        this.wounded = wounded;
    }

    public LivingEntity getLastAttacker() {
        return lastAttacker;
    }

    public void setLastAttacker(LivingEntity lastAttacker) {
        this.lastAttacker = lastAttacker;
    }

    public List<String> getLongTermMemory() {
        return longTermMemory;
    }

    public void addLongTermMemory(String fact) {
        if (fact != null && !fact.isBlank() && !longTermMemory.contains(fact)) {
            longTermMemory.add(fact);
            remember("Remembered fact: " + fact);
        }
    }

    public void clearLongTermMemory() {
        longTermMemory.clear();
        remember("Cleared all long-term memory.");
    }

    public Material getEquippedItem() {
        return equippedItem;
    }

    public void setEquippedItem(Material equippedItem) {
        this.equippedItem = equippedItem == null ? Material.AIR : equippedItem;
    }

    public void addItem(Material material, int amount) {
        if (material == null || material.isAir() || amount <= 0) {
            return;
        }
        inventory.merge(material, amount, Integer::sum);
    }

    public boolean removeItem(Material material, int amount) {
        if (material == null || amount <= 0) {
            return false;
        }
        int current = inventory.getOrDefault(material, 0);
        if (current < amount) {
            return false;
        }
        if (current == amount) {
            inventory.remove(material);
        } else {
            inventory.put(material, current - amount);
        }
        return true;
    }

    public int count(Material material) {
        return inventory.getOrDefault(material, 0);
    }

    public Map<Material, Integer> inventoryView() {
        return Collections.unmodifiableMap(inventory);
    }

    public void clearInventory() {
        inventory.clear();
    }

    public void remember(String entry) {
        memory.addLast(entry);
        while (memory.size() > 12) {
            memory.removeFirst();
        }
    }

    public String memorySummary() {
        if (memory.isEmpty()) {
            return "No memory yet.";
        }
        return String.join("\n", memory);
    }

    public String inventorySummary() {
        if (inventory.isEmpty()) {
            return "empty";
        }
        StringBuilder builder = new StringBuilder();
        inventory.forEach((material, amount) -> {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(material.name()).append(" x").append(amount);
        });
        return builder.toString();
    }
}