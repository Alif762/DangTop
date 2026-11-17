package my.dangtop;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.*;

public class DangTop extends JavaPlugin implements Listener, CommandExecutor {

    private static final int SIZE = 54;
    private static final int PER_PAGE = 45;
    private static final int PREV_SLOT = 45;  // bottom-left
    private static final int INFO_SLOT = 49;  // bottom-middle
    private static final int NEXT_SLOT = 53;  // bottom-right
    private static final int MAX_PLAYERS = 100;
    private static final long CACHE_MS = 30_000L;

    private static class TopHolder implements InventoryHolder {
        final int page;

        TopHolder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class Entry {
        final OfflinePlayer player;
        final String name;
        final double balance;

        Entry(OfflinePlayer player, String name, double balance) {
            this.player = player;
            this.name = name;
            this.balance = balance;
        }
    }

    private final List<Entry> top = new ArrayList<>();
    private long lastRefresh = 0;
    private Object eco;
    private Method getBalance;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("dangtop") != null) getCommand("dangtop").setExecutor(this);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // ---------- Command ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!p.hasPermission("dangtop.use")) return true;
        if (!hookEconomy()) {
            p.sendMessage(color("&cEconomy system (Vault) not found."));
            return true;
        }
        refreshIfNeeded();
        openPage(p, 1);
        return true;
    }

    // ---------- Data ----------
    private void refreshIfNeeded() {
        if (System.currentTimeMillis() - lastRefresh < CACHE_MS) return;
        lastRefresh = System.currentTimeMillis();

        List<Entry> list = new ArrayList<>();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            String name = op.getName();
            if (name == null) continue;
            double bal = balance(op);
            if (bal > 0) list.add(new Entry(op, name, bal));
        }
        list.sort((a, b) -> Double.compare(b.balance, a.balance));

        top.clear();
        for (int i = 0; i < list.size() && i < MAX_PLAYERS; i++) top.add(list.get(i));
    }

    // ---------- GUI ----------
    private void openPage(Player p, int page) {
        int totalPages = Math.max(1, (int) Math.ceil(top.size() / (double) PER_PAGE));
        page = Math.max(1, Math.min(page, totalPages));

        Inventory inv = Bukkit.createInventory(new TopHolder(page), SIZE,
                color("&8[ &6DangTop &8]"));

        // Bottom row filler
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta();
        pm.setDisplayName(" ");
        pane.setItemMeta(pm);
        for (int i = PER_PAGE; i < SIZE; i++) inv.setItem(i, pane);

        // Players
        int start = (page - 1) * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < top.size(); i++) {
            inv.setItem(i, head(top.get(start + i), start + i + 1));
        }

        // Arrows
        if (page > 1) {
            inv.setItem(PREV_SLOT, button(Material.ARROW, "&e\u2190 Previous Page",
                    "&7Go to page " + (page - 1)));
        }
        if (page < totalPages) {
            inv.setItem(NEXT_SLOT, button(Material.ARROW, "&eNext Page \u2192",
                    "&7Go to page " + (page + 1)));
        }

        // Info
        int myRank = rankOf(p);
        List<String> lore = new ArrayList<>();
        lore.add(color("&7Page &f" + page + "&7/&f" + totalPages));
        lore.add(color(myRank > 0
                ? "&7Your rank: &6#" + myRank
                : "&7Your rank: &8Not in the top " + MAX_PLAYERS));
        ItemStack info = new ItemStack(Material.NETHER_STAR);
        ItemMeta im = info.getItemMeta();
        im.setDisplayName(color("&6&lRichest Players"));
        im.setLore(lore);
        info.setItemMeta(im);
        inv.setItem(INFO_SLOT, info);

        p.openInventory(inv);
    }

    private ItemStack head(Entry e, int rank) {
        String nameColor = rank == 1 ? "&6&l" : rank == 2 ? "&f&l" : rank == 3 ? "&c&l" : "&e";
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta m = (SkullMeta) item.getItemMeta();
        m.setOwningPlayer(e.player);
        m.setDisplayName(color(nameColor + "#" + rank + " &f" + e.name));
        m.setLore(List.of(
                "",
                color("&7Balance: &a$" + String.format(Locale.US, "%,.2f", e.balance))));
        m.addItemFlags(ItemFlag.values());
        item.setItemMeta(m);
        return item;
    }

    private ItemStack button(Material mat, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta m = item.getItemMeta();
        m.setDisplayName(color(name));
        m.setLore(List.of(color(lore)));
        item.setItemMeta(m);
        return item;
    }

    private int rankOf(Player p) {
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).player.getUniqueId().equals(p.getUniqueId())) return i + 1;
        }
        return 0;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory inv = e.getView().getTopInventory();
        if (!(inv.getHolder() instanceof TopHolder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != inv) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int totalPages = Math.max(1, (int) Math.ceil(top.size() / (double) PER_PAGE));
        int slot = e.getSlot();

        if (slot == PREV_SLOT && h.page > 1) {
            int target = h.page - 1;
            Bukkit.getScheduler().runTask(this, () -> openPage(p, target));
        } else if (slot == NEXT_SLOT && h.page < totalPages) {
            int target = h.page + 1;
            Bukkit.getScheduler().runTask(this, () -> openPage(p, target));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof TopHolder) e.setCancelled(true);
    }

    // ---------- Money (Vault) ----------
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean hookEconomy() {
        if (eco != null) return true;
        try {
            Class c = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration(c);
            if (rsp == null) return false;
            eco = rsp.getProvider();
            getBalance = c.getMethod("getBalance", OfflinePlayer.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private double balance(OfflinePlayer p) {
        try {
            return ((Number) getBalance.invoke(eco, p)).doubleValue();
        } catch (Throwable t) {
            return 0;
        }
    }
    }
