package dev.steveai.plan;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class PlanParser {
    private static final Gson GSON = new Gson();
    private static final Set<String> ALLOWED_TYPES = Set.of(
        "say",
        "wait",
        "move_near_owner",
        "follow_owner",
        "stop_following",
        "protect_owner",
        "stop_protecting",
        "move_near_block",
        "break_owner_target_block",
        "break_nearest_block",
        "collect_items",
        "equip_best_tool",
        "place_block",
        "chop_tree",
        "mine_vein",
        "craft_item",
        "open_nearest_container",
        "attack_nearest_hostile",
        "flee_to_owner"
    );

    public Plan parse(String raw, int maxActions) {
        String jsonText = stripCodeFence(raw);
        JsonObject root = GSON.fromJson(jsonText, JsonObject.class);
        JsonArray actionsJson = root.getAsJsonArray("actions");
        if (actionsJson == null) {
            throw new IllegalArgumentException("Plan is missing actions array");
        }

        List<PlanAction> actions = new ArrayList<>();
        for (JsonElement element : actionsJson) {
            if (actions.size() >= maxActions) {
                break;
            }
            JsonObject object = element.getAsJsonObject();
            String type = getString(object, "type", "");
            if (!ALLOWED_TYPES.contains(type)) {
                throw new IllegalArgumentException("Unsupported survival action type: " + type);
            }
            actions.add(new PlanAction(
                type,
                getString(object, "text", ""),
                getString(object, "material", ""),
                "",
                Math.max(1, Math.min(getInt(object, "ticks", 20), 20 * 30)),
                Math.max(1, Math.min(getInt(object, "radius", 8), 32)),
                Math.max(1, Math.min(getInt(object, "amount", 1), 64)),
                0, 0, 0, 0, 0, 0, 0, 0, 0
            ));
        }
        return new Plan(actions);
    }

    private String stripCodeFence(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }

    private String getString(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private int getInt(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }
}