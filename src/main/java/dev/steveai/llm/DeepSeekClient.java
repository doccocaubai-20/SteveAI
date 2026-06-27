package dev.steveai.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.steveai.agent.SteveAgent;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class DeepSeekClient implements LlmClient {
    private static final Gson GSON = new Gson();

    private final JavaPlugin plugin;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    public DeepSeekClient(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletableFuture<String> plan(SteveAgent agent, String ownerName, String playerRequest) {
        String apiKey = plugin.getConfig().getString("llm.api-key", "");
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("PUT_YOUR")) {
            return CompletableFuture.failedFuture(new IllegalStateException("DeepSeek API key is missing in config.yml"));
        }

        String endpoint = plugin.getConfig().getString("llm.endpoint", "https://api.deepseek.com/chat/completions");
        String model = plugin.getConfig().getString("llm.model", "deepseek-chat");
        int maxOutputTokens = plugin.getConfig().getInt("llm.max-output-tokens", 900);
        double temperature = plugin.getConfig().getDouble("llm.temperature", 0.2);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxOutputTokens);

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt()));
        messages.add(message("user", userPrompt(agent, ownerName, playerRequest)));
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(45))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new RuntimeException("LLM request failed: HTTP " + response.statusCode() + " " + response.body());
                }
                try {
                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                    return json.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString();
                } catch (RuntimeException ex) {
                    throw new RuntimeException("Could not parse LLM response: " + response.body(), ex);
                }
            });
    }

    private JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private String systemPrompt() {
        return "You are the planner for a mortal survival-mode PaperMC NPC named Steve. "
            + "He must not use creative/admin powers, commands, teleporting, setblock, fill, or spawning items. "
            + "Return only compact JSON, no markdown. Materials must be Bukkit Material names. "
            + "Allowed actions: say{text}, wait{ticks}, move_near_owner{}, follow_owner{}, stop_following{}, protect_owner{}, stop_protecting{}, "
            + "move_near_block{material,radius}, break_owner_target_block{}, break_nearest_block{material,radius}, collect_items{radius}, "
            + "equip_best_tool{material}, place_block{material}, chop_tree{radius}, mine_vein{material,radius}, craft_item{material,amount}, "
            + "open_nearest_container{material,radius}, attack_nearest_hostile{radius}, flee_to_owner{}. "
            + "Use inventory honestly: to place or craft, Steve must already have items. "
            + "Examples: follow me -> {\"actions\":[{\"type\":\"follow_owner\"}]}. "
            + "Protect me -> {\"actions\":[{\"type\":\"protect_owner\"}]}. "
            + "Pick up drops -> {\"actions\":[{\"type\":\"collect_items\",\"radius\":6}]}. "
            + "Chop a tree -> {\"actions\":[{\"type\":\"chop_tree\",\"radius\":12},{\"type\":\"collect_items\",\"radius\":8}]}. "
            + "Mine iron vein -> {\"actions\":[{\"type\":\"mine_vein\",\"material\":\"IRON_ORE\",\"radius\":12}]}. "
            + "Craft sticks -> {\"actions\":[{\"type\":\"craft_item\",\"material\":\"STICK\",\"amount\":1}]}. "
            + "Open chest -> {\"actions\":[{\"type\":\"open_nearest_container\",\"material\":\"CHEST\",\"radius\":8}]}. "
            + "Schema: {\"actions\":[{\"type\":\"...\"}]}";
    }

    private String userPrompt(SteveAgent agent, String ownerName, String playerRequest) {
        Location loc = agent.getLocation();
        return "Agent: " + agent.getName() + "\n"
            + "Owner: " + ownerName + "\n"
            + "Mode: mortal survival helper\n"
            + "Agent state: busy=" + agent.isBusy() + ", following=" + agent.isFollowing() + ", protecting=" + agent.isProtecting() + ", dead=" + agent.isDead() + "\n"
            + "Equipped: " + agent.getEquippedItem().name() + "\n"
            + "Inventory: " + agent.inventorySummary() + "\n"
            + "Agent location: " + loc.getWorld().getName() + " "
            + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ() + "\n"
            + "Memory:\n" + agent.memorySummary() + "\n"
            + "Player request: " + playerRequest;
    }
}