package dev.steveai.agent;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class AgentManager {
    private final Map<String, SteveAgent> agents = new LinkedHashMap<>();

    public SteveAgent create(String name, Player owner) {
        SteveAgent agent = new SteveAgent(name, owner);
        agents.put(key(name), agent);
        return agent;
    }

    public SteveAgent createLoaded(String name, UUID ownerId, Location location) {
        SteveAgent agent = new SteveAgent(name, ownerId, location);
        agents.put(key(name), agent);
        return agent;
    }

    public void add(SteveAgent agent) {
        agents.put(key(agent.getName()), agent);
    }

    public Optional<SteveAgent> find(String name) {
        return Optional.ofNullable(agents.get(key(name)));
    }

    public Optional<SteveAgent> findByNpcId(int npcId) {
        return agents.values().stream()
            .filter(agent -> agent.getCitizensNpcId().isPresent() && agent.getCitizensNpcId().getAsInt() == npcId)
            .findFirst();
    }

    public boolean remove(String name) {
        return agents.remove(key(name)) != null;
    }

    public boolean remove(SteveAgent agent) {
        return agents.remove(key(agent.getName())) != null;
    }

    public Collection<SteveAgent> all() {
        return agents.values();
    }

    public void clear() {
        agents.clear();
    }

    private String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}