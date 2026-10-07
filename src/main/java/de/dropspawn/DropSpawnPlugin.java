package de.dropspawn;

import org.bukkit.plugin.java.JavaPlugin;

/** Ein Plugin mit beiden Befehlen: /drop (Items) und /spawning (Mobs). */
public class DropSpawnPlugin extends JavaPlugin {

    private DropFeature drop;
    private SpawnFeature spawn;

    @Override
    public void onEnable() {
        drop = new DropFeature(this);
        spawn = new SpawnFeature(this);
        drop.enable();
        spawn.enable();
        getCommand("drop").setExecutor(drop);
        getCommand("spawning").setExecutor(spawn);
    }

    @Override
    public void onDisable() {
        if (drop != null) drop.disable();
        if (spawn != null) spawn.disable();
    }
}
