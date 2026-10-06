package de.dropmenu;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class DropPlugin extends JavaPlugin implements Listener {

    private static final int MAIN_SIZE = 54;
    private static final int ITEMS_PER_PAGE = 45;
    private static final int STACKS_PER_TICK = 10;

    /** Alle droppbaren Materialien, einmal beim Start berechnet. */
    private final List<Material> allItems = new ArrayList<>();

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, BukkitTask> dropTasks = new HashMap<>();

    // ------------------------------------------------------------------
    // Datenklassen
    // ------------------------------------------------------------------

    private static class Session {
        final Material[] slots = new Material[MAIN_SIZE];
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
        final List<Material> list;
        SelectHolder(int targetSlot, String search, int page, List<Material> list) {
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

    @Override
    public void onEnable() {
        for (Material m : Material.values()) {
            if (m.name().startsWith("LEGACY_") || m.isAir() || !m.isItem()) continue;
            allItems.add(m);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info(allItems.size() + " Items geladen.");
    }

    @Override
    public void onDisable() {
        dropTasks.values().forEach(BukkitTask::cancel);
        dropTasks.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Nur für Spieler.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("stop")) {
            BukkitTask t = dropTasks.remove(p.getUniqueId());
            if (t != null) {
                t.cancel();
                msg(p, ChatColor.YELLOW + "Dein laufender Drop wurde gestoppt.");
            } else {
                msg(p, ChatColor.GRAY + "Es läuft kein Drop.");
            }
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
        return sessions.computeIfAbsent(p.getUniqueId(), k -> new Session());
    }

    private void msg(Player p, String text) {
        p.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "Drop" + ChatColor.DARK_GRAY + "] " + text);
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

    private String pretty(Material m) {
        String[] parts = m.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Menüs
    // ------------------------------------------------------------------

    private void openMain(Player p) {
        Session s = session(p);
        MainHolder holder = new MainHolder();
        Inventory inv = Bukkit.createInventory(holder, MAIN_SIZE, ChatColor.DARK_GRAY + "Drop-Menü");
        holder.inv = inv;

        for (int i = 0; i < MAIN_SIZE; i++) {
            Material m = s.slots[i];
            if (m == null) {
                inv.setItem(i, named(Material.GRAY_STAINED_GLASS_PANE, ChatColor.GRAY + "Nicht eingestellt",
                        ChatColor.DARK_GRAY + "Klicken, um ein Item zu wählen"));
            } else {
                ItemStack it = new ItemStack(m);
                ItemMeta meta = it.getItemMeta();
                if (meta != null) {
                    meta.setLore(List.of(
                            ChatColor.GREEN + "Linksklick: Menge eingeben & droppen",
                            ChatColor.RED + "Rechtsklick: Slot zurücksetzen"));
                    it.setItemMeta(meta);
                }
                inv.setItem(i, it);
            }
        }
        p.openInventory(inv);
    }

    private void openSelect(Player p, int targetSlot, String search, int page) {
        List<Material> list = new ArrayList<>();
        if (search == null || search.isBlank()) {
            list.addAll(allItems);
        } else {
            String q = search.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
            for (Material m : allItems) {
                if (m.name().toLowerCase(Locale.ROOT).contains(q)) list.add(m);
            }
        }

        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) ITEMS_PER_PAGE));
        page = Math.max(0, Math.min(page, pages - 1));

        SelectHolder holder = new SelectHolder(targetSlot, search, page, list);
        String title = ChatColor.DARK_GRAY + "Item wählen " + (page + 1) + "/" + pages;
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.inv = inv;

        int start = page * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE && start + i < list.size(); i++) {
            inv.setItem(i, new ItemStack(list.get(start + i)));
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
        Material m = s.slots[slot];

        if (m == null) {
            openSelect(p, slot, null, 0);
            return;
        }

        if (click.isRightClick()) {
            s.slots[slot] = null;
            openMain(p);
            return;
        }

        // Linksklick: Menge im Chat abfragen
        s.pending = new Pending(PendingType.AMOUNT, slot);
        Bukkit.getScheduler().runTask(this, p::closeInventory);
        msg(p, ChatColor.GREEN + "Wie viele " + ChatColor.WHITE + pretty(m) + ChatColor.GREEN
                + " sollen gedroppt werden? Zahl in den Chat schreiben (" + ChatColor.GRAY + "cancel"
                + ChatColor.GREEN + " zum Abbrechen).");
    }

    private void handleSelect(Player p, SelectHolder h, int slot) {
        if (slot < ITEMS_PER_PAGE) {
            int idx = h.page * ITEMS_PER_PAGE + slot;
            if (idx < h.list.size()) {
                session(p).slots[h.targetSlot] = h.list.get(idx);
                openMain(p);
            }
            return;
        }
        switch (slot) {
            case 45 -> { if (h.page > 0) openSelect(p, h.targetSlot, h.search, h.page - 1); }
            case 47 -> {
                session(p).pending = new Pending(PendingType.SEARCH, h.targetSlot);
                Bukkit.getScheduler().runTask(this, p::closeInventory);
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

        Bukkit.getScheduler().runTask(this, () -> {
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
            Material m = s.slots[pending.slot];
            if (m == null) {
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
            startDrop(p, m, amount);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        sessions.remove(id);
        BukkitTask t = dropTasks.remove(id);
        if (t != null) t.cancel();
    }

    // ------------------------------------------------------------------
    // Droppen
    // ------------------------------------------------------------------

    /**
     * Droppt die Menge in Stacks. Pro Tick werden nur wenige Stacks gedroppt,
     * damit der Server auch bei riesigen Mengen nicht einfriert.
     * Mit /drop stop kann der Vorgang abgebrochen werden.
     */
    private void startDrop(Player p, Material m, long amount) {
        BukkitTask old = dropTasks.remove(p.getUniqueId());
        if (old != null) old.cancel();

        msg(p, ChatColor.GREEN + "Droppe " + ChatColor.WHITE + String.format(Locale.GERMAN, "%,d", amount)
                + "x " + pretty(m) + ChatColor.GREEN + " ... (" + ChatColor.GRAY + "/drop stop"
                + ChatColor.GREEN + " zum Abbrechen)");

        final int maxStack = Math.max(1, m.getMaxStackSize());

        BukkitTask task = new BukkitRunnable() {
            long remaining = amount;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    dropTasks.remove(p.getUniqueId());
                    return;
                }
                for (int i = 0; i < STACKS_PER_TICK && remaining > 0; i++) {
                    int n = (int) Math.min(maxStack, remaining);
                    remaining -= n;

                    Location eye = p.getEyeLocation();
                    Vector dir = eye.getDirection();
                    Item item = p.getWorld().dropItem(eye.clone().add(dir.clone().multiply(0.3)).subtract(0, 0.3, 0),
                            new ItemStack(m, n));
                    item.setVelocity(dir.multiply(0.3));
                    item.setPickupDelay(40);
                }
                if (remaining <= 0) {
                    cancel();
                    dropTasks.remove(p.getUniqueId());
                    msg(p, ChatColor.GREEN + "Fertig gedroppt.");
                }
            }
        }.runTaskTimer(this, 0L, 1L);

        dropTasks.put(p.getUniqueId(), task);
    }
}
