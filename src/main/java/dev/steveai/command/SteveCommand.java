package dev.steveai.command;

import dev.steveai.StevePaperAIPlugin;
import dev.steveai.agent.AgentManager;
import dev.steveai.agent.SteveAgent;
import dev.steveai.llm.LlmClient;
import dev.steveai.npc.CitizensNpcController;
import dev.steveai.plan.Plan;
import dev.steveai.plan.PlanParser;
import dev.steveai.storage.AgentStore;
import dev.steveai.task.TaskRunner;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

public final class SteveCommand implements CommandExecutor, TabCompleter {
    private final StevePaperAIPlugin plugin;
    private final AgentManager agentManager;
    private final LlmClient llmClient;
    private final TaskRunner taskRunner;
    private final CitizensNpcController npcController;
    private final AgentStore agentStore;
    private final PlanParser planParser = new PlanParser();

    public SteveCommand(
        StevePaperAIPlugin plugin,
        AgentManager agentManager,
        LlmClient llmClient,
        TaskRunner taskRunner,
        CitizensNpcController npcController,
        AgentStore agentStore
    ) {
        this.plugin = plugin;
        this.agentManager = agentManager;
        this.llmClient = llmClient;
        this.taskRunner = taskRunner;
        this.npcController = npcController;
        this.agentStore = agentStore;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "spawn" -> spawn(sender, args);
            case "remove" -> remove(sender, args);
            case "ask" -> ask(sender, args);
            case "follow" -> direct(sender, args, "follow_owner");
            case "protect" -> direct(sender, args, "protect_owner");
            case "unprotect" -> direct(sender, args, "stop_protecting");
            case "sit" -> sit(sender, args);
            case "standup", "stand" -> standup(sender, args);
            case "come" -> direct(sender, args, "move_near_owner");
            case "attack" -> direct(sender, args, "attack_nearest_hostile");
            case "collect" -> direct(sender, args, "collect_items");
            case "stop" -> stop(sender, args);
            case "status" -> status(sender);
            case "inventory", "inv" -> inventory(sender, args);
            case "cleanup" -> cleanup(sender, args);
            case "adopt" -> adopt(sender, args);
            case "reload" -> reload(sender);
            default -> {
                help(sender);
                yield true;
            }
        };
    }

    private boolean spawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can spawn an agent.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /steve spawn <name>");
            return true;
        }
        if (!npcController.isAvailable()) {
            sender.sendMessage(ChatColor.RED + "Citizens is not enabled on this server.");
            return true;
        }

        agentManager.find(args[1]).ifPresent(existing -> {
            taskRunner.stopAgent(existing);
            npcController.destroy(existing);
        });
        SteveAgent agent = agentManager.create(args[1], player);
        npcController.spawn(agent, player.getLocation());
        agentStore.saveAsync(agentManager);
        sender.sendMessage(ChatColor.GREEN + "Created survival NPC agent " + ChatColor.WHITE + agent.getName()
            + ChatColor.GREEN + " with id " + agent.getCitizensNpcId().orElse(-1) + ".");
        return true;
    }

    private boolean remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /steve remove <name>");
            return true;
        }
        Optional<SteveAgent> agent = agentManager.find(args[1]);
        agent.ifPresent(found -> {
            taskRunner.stopAgent(found);
            npcController.destroy(found);
        });
        boolean removed = agentManager.remove(args[1]);
        agentStore.saveAsync(agentManager);
        sender.sendMessage(removed ? ChatColor.GREEN + "Removed agent and NPC." : ChatColor.RED + "Agent not found.");
        return true;
    }

    private boolean ask(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can ask an agent.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /steve ask <name> <request>");
            return true;
        }

        String name = args[1];
        Optional<SteveAgent> found = agentManager.find(name);
        if (found.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Agent not found. Use /steve spawn " + name + " first, or /steve adopt " + name + ".");
            return true;
        }

        SteveAgent agent = found.get();
        if (!agent.getOwnerId().equals(player.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "Only the owner can control this agent.");
            return true;
        }
        if (agent.isDead()) {
            sender.sendMessage(ChatColor.RED + agent.getName() + " is dead.");
            return true;
        }
        if (agent.isWounded()) {
            sender.sendMessage(ChatColor.RED + agent.getName() + " is wounded and cannot act.");
            return true;
        }

        String request = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        sender.sendMessage(ChatColor.GRAY + "Thinking with DeepSeek...");
        llmClient.plan(agent, player.getName(), request).whenComplete((rawPlan, error) -> {
            if (error != null) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ChatColor.RED + "LLM error: " + error.getMessage()));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    int maxActions = plugin.getConfig().getInt("agent.max-actions-per-plan", 30);
                    Plan plan = planParser.parse(rawPlan, maxActions);
                    taskRunner.enqueue(agent, plan);
                    agent.remember("Owner requested: " + request);
                    agentStore.saveAsync(agentManager);
                    player.sendMessage(ChatColor.GREEN + "Queued " + plan.actions().size() + " action(s) for " + agent.getName() + ".");
                } catch (RuntimeException ex) {
                    player.sendMessage(ChatColor.RED + "Plan parse error: " + ex.getMessage());
                    player.sendMessage(ChatColor.GRAY + "Raw response: " + rawPlan);
                }
            });
        });
        return true;
    }


    private boolean direct(CommandSender sender, String[] args, String actionType) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /steve " + args[0] + " <name>");
            return true;
        }
        Optional<SteveAgent> found = agentManager.find(args[1]);
        if (found.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Agent not found.");
            return true;
        }
        SteveAgent agent = found.get();
        if (sender instanceof Player player && !agent.getOwnerId().equals(player.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "Only the owner can control this agent.");
            return true;
        }
        if (agent.isDead()) {
            sender.sendMessage(ChatColor.RED + agent.getName() + " is dead.");
            return true;
        }
        if (agent.isWounded()) {
            sender.sendMessage(ChatColor.RED + agent.getName() + " is wounded and cannot act.");
            return true;
        }
        Plan plan = new Plan(List.of(new dev.steveai.plan.PlanAction(actionType, "", "", "", 20, 8, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
        taskRunner.enqueue(agent, plan);
        agent.setSitting(false);
        agentStore.saveAsync(agentManager);
        sender.sendMessage(ChatColor.GREEN + "Queued " + actionType + " for " + agent.getName() + ".");
        return true;
    }

    private boolean sit(CommandSender sender, String[] args) {
        Optional<SteveAgent> found = requireAgent(sender, args);
        if (found.isEmpty()) {
            return true;
        }
        SteveAgent agent = found.get();
        if (agent.isDead()) {
            sender.sendMessage(ChatColor.RED + agent.getName() + " is dead.");
            return true;
        }
        if (agent.isWounded()) {
            sender.sendMessage(ChatColor.RED + agent.getName() + " is wounded and cannot act.");
            return true;
        }
        taskRunner.stopAgent(agent);
        agent.setSitting(true);
        agent.setFollowing(false);
        agent.setProtecting(false);
        agentStore.saveAsync(agentManager);
        sender.sendMessage(ChatColor.GREEN + agent.getName() + " is sitting/standing by.");
        return true;
    }

    private boolean standup(CommandSender sender, String[] args) {
        Optional<SteveAgent> found = requireAgent(sender, args);
        if (found.isEmpty()) {
            return true;
        }
        SteveAgent agent = found.get();
        if (agent.isDead()) {
            sender.sendMessage(ChatColor.RED + agent.getName() + " is dead.");
            return true;
        }
        if (agent.isWounded()) {
            sender.sendMessage(ChatColor.RED + agent.getName() + " is wounded and cannot act.");
            return true;
        }
        agent.setSitting(false);
        agentStore.saveAsync(agentManager);
        sender.sendMessage(ChatColor.GREEN + agent.getName() + " is ready.");
        return true;
    }

    private Optional<SteveAgent> requireAgent(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /steve " + args[0] + " <name>");
            return Optional.empty();
        }
        Optional<SteveAgent> found = agentManager.find(args[1]);
        if (found.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Agent not found.");
            return Optional.empty();
        }
        if (sender instanceof Player player && !found.get().getOwnerId().equals(player.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "Only the owner can control this agent.");
            return Optional.empty();
        }
        return found;
    }
    private boolean stop(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /steve stop <name>");
            return true;
        }
        Optional<SteveAgent> agent = agentManager.find(args[1]);
        if (agent.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Agent not found.");
            return true;
        }
        taskRunner.stopAgent(agent.get());
        agentStore.saveAsync(agentManager);
        sender.sendMessage(ChatColor.GREEN + "Stopped " + agent.get().getName() + ".");
        return true;
    }

    private boolean status(CommandSender sender) {
        if (agentManager.all().isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No agents yet.");
            return true;
        }
        for (SteveAgent agent : agentManager.all()) {
            int npcId = agent.getCitizensNpcId().orElse(-1);
            sender.sendMessage(ChatColor.GREEN + agent.getName() + ChatColor.GRAY
                + " owner=" + agent.getOwnerId()
                + " busy=" + agent.isBusy()
                + " follow=" + agent.isFollowing()
                + " protect=" + agent.isProtecting()
                + " sitting=" + agent.isSitting()
                + " dead=" + agent.isDead()
                + " wounded=" + agent.isWounded()
                + " npcId=" + npcId);
        }
        return true;
    }

    private boolean inventory(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /steve inventory <name>");
            return true;
        }
        Optional<SteveAgent> agent = agentManager.find(args[1]);
        if (agent.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Agent not found.");
            return true;
        }
        sender.sendMessage(ChatColor.GREEN + agent.get().getName() + ChatColor.GRAY + " inventory: " + agent.get().inventorySummary());
        return true;
    }

    private boolean cleanup(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /steve cleanup <name>");
            return true;
        }
        Optional<SteveAgent> managed = agentManager.find(args[1]);
        managed.ifPresent(found -> {
            taskRunner.stopAgent(found);
            agentManager.remove(found);
        });
        int removed = npcController.cleanupByName(args[1]);
        agentStore.saveAsync(agentManager);
        sender.sendMessage(ChatColor.GREEN + "Removed " + removed + " Citizens NPC(s) named " + args[1] + ".");
        return true;
    }

    private boolean adopt(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can adopt an NPC.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /steve adopt <name> [npcId]");
            return true;
        }
        Optional<NPC> npc = Optional.empty();
        if (args.length >= 3) {
            try {
                npc = npcController.findById(Integer.parseInt(args[2]));
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "NPC id must be a number.");
                return true;
            }
        } else {
            npc = npcController.findFirstByName(args[1]);
        }
        if (npc.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Citizens NPC not found.");
            return true;
        }
        SteveAgent agent = agentManager.find(args[1]).orElseGet(() -> agentManager.createLoaded(args[1], player.getUniqueId(), player.getLocation()));
        npcController.adopt(agent, npc.get());
        agentStore.saveAsync(agentManager);
        sender.sendMessage(ChatColor.GREEN + "Adopted Citizens NPC " + npc.get().getName() + " id=" + npc.get().getId() + " as " + agent.getName() + ".");
        return true;
    }

    private boolean reload(CommandSender sender) {
        plugin.reloadSteveConfig();
        sender.sendMessage(ChatColor.GREEN + "StevePaperAI config reloaded.");
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + "StevePaperAI commands:");
        sender.sendMessage(ChatColor.GRAY + "/steve spawn <name>");
        sender.sendMessage(ChatColor.GRAY + "/steve ask <name> <request>");
        sender.sendMessage(ChatColor.GRAY + "/steve follow|protect|unprotect|sit|standup|come|attack|collect <name>");
        sender.sendMessage(ChatColor.GRAY + "/steve inventory <name>");
        sender.sendMessage(ChatColor.GRAY + "/steve stop <name>");
        sender.sendMessage(ChatColor.GRAY + "/steve remove <name>");
        sender.sendMessage(ChatColor.GRAY + "/steve cleanup <name>");
        sender.sendMessage(ChatColor.GRAY + "/steve adopt <name> [npcId]");
        sender.sendMessage(ChatColor.GRAY + "/steve status");
        sender.sendMessage(ChatColor.GRAY + "/steve reload");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("spawn", "ask", "follow", "protect", "unprotect", "sit", "standup", "come", "attack", "collect", "inventory", "stop", "remove", "cleanup", "adopt", "status", "reload"), args[0]);
        }
        if (args.length == 2 && List.of("ask", "follow", "protect", "unprotect", "sit", "standup", "come", "attack", "collect", "inventory", "stop", "remove", "cleanup", "adopt").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(agentManager.all().stream().map(SteveAgent::getName).collect(Collectors.toList()), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(value);
            }
        }
        return matches;
    }
}