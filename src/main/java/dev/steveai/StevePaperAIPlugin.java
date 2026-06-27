package dev.steveai;

import dev.steveai.agent.AgentManager;
import dev.steveai.agent.SteveAgent;
import dev.steveai.command.SteveCommand;
import dev.steveai.listener.SurvivalNpcListener;
import dev.steveai.llm.DeepSeekClient;
import dev.steveai.llm.LlmClient;
import dev.steveai.npc.CitizensNpcController;
import dev.steveai.storage.AgentStore;
import dev.steveai.task.TaskRunner;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class StevePaperAIPlugin extends JavaPlugin {
    private AgentManager agentManager;
    private TaskRunner taskRunner;
    private LlmClient llmClient;
    private CitizensNpcController npcController;
    private AgentStore agentStore;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.llmClient = new DeepSeekClient(this);
        this.agentManager = new AgentManager();
        this.npcController = new CitizensNpcController(this);
        this.agentStore = new AgentStore(this);

        if (!npcController.isAvailable()) {
            getLogger().severe("Citizens is required. Install Citizens before using StevePaperAI.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.agentStore.load(agentManager);
        this.taskRunner = new TaskRunner(this, agentManager, npcController);
        this.taskRunner.start();

        getServer().getPluginManager().registerEvents(new SurvivalNpcListener(agentManager, taskRunner, npcController, agentStore), this);

        SteveCommand steveCommand = new SteveCommand(this, agentManager, llmClient, taskRunner, npcController, agentStore);
        PluginCommand command = getCommand("steve");
        if (command != null) {
            command.setExecutor(steveCommand);
            command.setTabCompleter(steveCommand);
        }

        getLogger().info("StevePaperAI enabled with survival inventory, persistence, and Citizens NPC support.");
    }

    @Override
    public void onDisable() {
        if (agentStore != null && agentManager != null) {
            agentStore.save(agentManager);
        }
        if (taskRunner != null) {
            taskRunner.stop();
        }
    }

    public void reloadSteveConfig() {
        reloadConfig();
        this.llmClient = new DeepSeekClient(this);
    }
}