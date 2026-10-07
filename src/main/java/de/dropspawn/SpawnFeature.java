package de.dropspawn;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class SpawnFeature implements Listener, CommandExecutor {

    private final JavaPlugin plugin;

    public SpawnFeature(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private static final int MAIN_SIZE = 54;
    private static final int ITEMS_PER_PAGE = 45;

    /** Alle spawnbaren Mobs, einmal beim Start berechnet. */
    private final List<EntityType> allTypes = new ArrayList<>();

    private final Map<UUID, Session> sessions = new HashMap<>();

    // ------------------------------------------------------------------
    // Datenklassen
    // ------------------------------------------------------------------

    private static class Session {
        final EntityType[] slots = new EntityType[MAIN_SIZE];
        Pending pending;
    }

    private enum PendingType { AMOUNT, SEARCH }

    private static class Pending {
        final PendingType type;
        final int slot;
        Pending(PendingType type, int slot) {
            this.type = type;
            this.slot = slot;
        }
    }

    private static class MainHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static class SelectHolder implements InventoryHolder {
        private Inventory inv;
        final int targetSlot;
        final String search;
        final int page;
        final List<EntityType> list;
        SelectHolder(int targetSlot, String search, int page, List<EntityType> list) {
            this.targetSlot = targetSlot;
            this.search = search;
            this.page = page;
            this.list = list;
        }
        @Override public Inventory getInventory() { return inv; }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void enable() {
        for (EntityType t : Registry.ENTITY_TYPE) {
            if (t.isSpawnable() && t.isAlive()) allTypes.add(t);
        }
        allTypes.sort(Comparator.comparing(this::id));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info(allTypes.size() + " Mobs geladen.");
    }

    public void disable() {
        sessions.forEach(this::saveSession);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Nur für Spieler.");
            return true;
        }
        session(p).pending = null;
        openMain(p);
        return true;
    }

    // ------------------------------------------------------------------
    // Hilfsmethoden
    // ------------------------------------------------------------------

    private Session session(Player p) {
        return sessions.computeIfAbsent(p.getUniqueId(), this::loadSession);
    }

    /** Technischer Name des Mobs, z. B. "iron_golem". */
    private String id(EntityType t) {
        NamespacedKey key = Registry.ENTITY_TYPE.getKey(t);
        return key == null ? "unknown" : key.getKey();
    }

    private String pretty(EntityType t) {
        String[] parts = id(t).split("_");
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1));
        }
        return sb.toString();
    }

    /** Spawn-Ei des Mobs als Symbol; falls es keins gibt, ein Namensschild. */
    private Material iconFor(EntityType t) {
        Material egg = Material.matchMaterial(id(t) + "_spawn_egg");
        if (egg != null) return egg;
        if (id(t).equals("armor_stand")) return Material.ARMOR_STAND;
        return Material.NAME_TAG;
    }

    private void msg(Player p, String text) {
        p.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "Spawning" + ChatColor.DARK_GRAY + "] " + text);
    }

    private ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) {
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add(s);
            meta.setLore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    // ------------------------------------------------------------------
    // Speichern / Laden (bleibt nach Rejoin und Neustart erhalten)
    // ------------------------------------------------------------------

    private File dataFile(UUID id) {
        return new File(new File(new File(plugin.getDataFolder(), "spawning"), "data"), id + ".yml");
    }

    private Session loadSession(UUID id) {
        Session s = new Session();
        File f = dataFile(id);
        if (!f.exists()) return s;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        for (int i = 0; i < MAIN_SIZE; i++) {
            String name = cfg.getString("slots." + i);
            if (name == null) continue;
            EntityType t = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(name));
            if (t != null && allTypes.contains(t)) s.slots[i] = t;
        }
        return s;
    }

    private void saveSession(UUID id, Session s) {
        YamlConfiguration cfg = new YamlConfiguration();
        for (int i = 0; i < MAIN_SIZE; i++) {
            if (s.slots[i] != null) cfg.set("slots." + i, id(s.slots[i]));
        }
        File f = dataFile(id);
        f.getParentFile().mkdirs();
        try {
            cfg.save(f);
        } catch (IOException ex) {
            plugin.getLogger().warning("Konnte Spawning-Daten nicht speichern (" + id + "): " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Menüs
    // ------------------------------------------------------------------

    private void openMain(Player p) {
        Session s = session(p);
        MainHolder holder = new MainHolder();
        Inventory inv = Bukkit.createInventory(holder, MAIN_SIZE, ChatColor.DARK_GRAY + "Spawning-Menü");
        holder.inv = inv;

        for (int i = 0; i < MAIN_SIZE; i++) {
            EntityType t = s.slots[i];
            if (t == null) {
                inv.setItem(i, named(Material.GRAY_STAINED_GLASS_PANE, ChatColor.GRAY + "Nicht eingestellt",
                        ChatColor.DARK_GRAY + "Klicken, um einen Mob zu wählen"));
            } else {
                inv.setItem(i, named(iconFor(t), ChatColor.WHITE + pretty(t),
                        ChatColor.GREEN + "Linksklick: Menge eingeben & spawnen",
                        ChatColor.RED + "Rechtsklick: Slot zurücksetzen"));
            }
        }
        p.openInventory(inv);
    }

    private void openSelect(Player p, int targetSlot, String search, int page) {
        List<EntityType> list = new ArrayList<>();
        if (search == null || search.isBlank()) {
            list.addAll(allTypes);
        } else {
            String q = search.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
            for (EntityType t : allTypes) {
                if (id(t).contains(q)) list.add(t);
            }
        }

        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) ITEMS_PER_PAGE));
        page = Math.max(0, Math.min(page, pages - 1));

        SelectHolder holder = new SelectHolder(targetSlot, search, page, list);
        String title = ChatColor.DARK_GRAY + "Mob wählen " + (page + 1) + "/" + pages;
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.inv = inv;

        int start = page * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE && start + i < list.size(); i++) {
            EntityType t = list.get(start + i);
            inv.setItem(i, named(iconFor(t), ChatColor.WHITE + pretty(t)));
        }

        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);

        if (page > 0) inv.setItem(45, named(Material.ARROW, ChatColor.YELLOW + "« Vorherige Seite"));
        inv.setItem(47, named(Material.OAK_SIGN, ChatColor.AQUA + "Suchen",
                ChatColor.GRAY + (search == null || search.isBlank() ? "Klicken und Begriff in den Chat schreiben"
                        : "Aktuell: " + search)));
        inv.setItem(49, named(Material.BARRIER, ChatColor.RED + "Zurück"));
        if (search != null && !search.isBlank()) {
            inv.setItem(51, named(Material.PAPER, ChatColor.YELLOW + "Suche zurücksetzen"));
        }
        if (page < pages - 1) inv.setItem(53, named(Material.ARROW, ChatColor.YELLOW + "Nächste Seite »"));

        p.openInventory(inv);
    }

    // ------------------------------------------------------------------
    // Klicks
    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (!(holder instanceof MainHolder) && !(holder instanceof SelectHolder)) return;

        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        if (holder instanceof MainHolder) {
            handleMain(p, slot, e.getClick());
        } else {
            handleSelect(p, (SelectHolder) holder, slot);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof MainHolder || holder instanceof SelectHolder) e.setCancelled(true);
    }

    private void handleMain(Player p, int slot, ClickType click) {
        Session s = session(p);
        EntityType t = s.slots[slot];

        if (t == null) {
            openSelect(p, slot, null, 0);
            return;
        }

        if (click.isRightClick()) {
            s.slots[slot] = null;
            saveSession(p.getUniqueId(), s);
            openMain(p);
            return;
        }

        // Linksklick: Menge im Chat abfragen
        s.pending = new Pending(PendingType.AMOUNT, slot);
        Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
        msg(p, ChatColor.GREEN + "Wie viele " + ChatColor.WHITE + pretty(t) + ChatColor.GREEN
                + " sollen gespawnt werden? Zahl in den Chat schreiben (" + ChatColor.GRAY + "cancel"
                + ChatColor.GREEN + " zum Abbrechen).");
    }

    private void handleSelect(Player p, SelectHolder h, int slot) {
        if (slot < ITEMS_PER_PAGE) {
            int idx = h.page * ITEMS_PER_PAGE + slot;
            if (idx < h.list.size()) {
                Session sess = session(p);
                sess.slots[h.targetSlot] = h.list.get(idx);
                saveSession(p.getUniqueId(), sess);
                openMain(p);
            }
            return;
        }
        switch (slot) {
            case 45 -> { if (h.page > 0) openSelect(p, h.targetSlot, h.search, h.page - 1); }
            case 47 -> {
                session(p).pending = new Pending(PendingType.SEARCH, h.targetSlot);
                Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
                msg(p, ChatColor.AQUA + "Gib einen Suchbegriff in den Chat ein (" + ChatColor.GRAY + "cancel"
                        + ChatColor.AQUA + " zum Abbrechen).");
            }
            case 49 -> openMain(p);
            case 51 -> { if (h.search != null && !h.search.isBlank()) openSelect(p, h.targetSlot, null, 0); }
            case 53 -> openSelect(p, h.targetSlot, h.search, h.page + 1);
            default -> { }
        }
    }

    // ------------------------------------------------------------------
    // Chat-Eingabe
    // ------------------------------------------------------------------

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.pending == null) return;

        e.setCancelled(true);
        final Pending pending = s.pending;
        final String text = e.getMessage().trim();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (s.pending != pending) return;

            if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("abbrechen")) {
                s.pending = null;
                openMain(p);
                return;
            }

            if (pending.type == PendingType.SEARCH) {
                s.pending = null;
                openSelect(p, pending.slot, text, 0);
                return;
            }

            // AMOUNT
            EntityType t = s.slots[pending.slot];
            if (t == null) {
                s.pending = null;
                return;
            }
            long amount;
            try {
                amount = Long.parseLong(text.replace(".", "").replace(",", ""));
            } catch (NumberFormatException ex) {
                msg(p, ChatColor.RED + "Das ist keine gültige Zahl. Versuch es nochmal oder schreib cancel.");
                return;
            }
            if (amount <= 0) {
                msg(p, ChatColor.RED + "Die Zahl muss größer als 0 sein.");
                return;
            }
            s.pending = null;
            startSpawn(p, t, amount);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Session sess = sessions.remove(id);
        if (sess != null) saveSession(id, sess);
    }

    // ------------------------------------------------------------------
    // Spawnen
    // ------------------------------------------------------------------

    /** Spawnt die komplette Menge sofort vor dem Spieler, alles auf einmal, ohne Limit. */
    private void startSpawn(Player p, EntityType type, long amount) {
        msg(p, ChatColor.GREEN + "Spawne " + ChatColor.WHITE + String.format(Locale.GERMAN, "%,d", amount)
                + "x " + pretty(type) + ChatColor.GREEN + " ...");

        Location loc = p.getLocation();
        Vector dir = loc.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-6) dir = new Vector(0, 0, 1);
        dir.normalize();
        Location base = loc.clone().add(dir.multiply(2.5));

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        long spawned = 0;
        try {
            while (spawned < amount) {
                Location at = base.clone().add(rnd.nextDouble(-0.6, 0.6), 0, rnd.nextDouble(-0.6, 0.6));
                p.getWorld().spawnEntity(at, type);
                spawned++;
            }
        } catch (Exception ex) {
            msg(p, ChatColor.RED + pretty(type) + " konnte nicht gespawnt werden (" + spawned + " gespawnt).");
            return;
        }
        msg(p, ChatColor.GREEN + "Fertig gespawnt.");
    }
}
