package dev.dynasell;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Menú de venta: metes ítems, cierras el menú y se venden. */
public class SellMenu implements Listener {

    public static final int SIZE = 54;
    public static final int ITEM_SLOTS = 45; // slots 0..44 para ítems
    public static final int INFO_SLOT = 49;

    static class Holder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final DynaSell plugin;

    public SellMenu(DynaSell plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, SIZE,
                DynaSell.c(plugin.getConfig().getString("menu.title", "&8Vender ítems")));
        h.inv = inv;

        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta();
        pm.displayName(Component.text(" "));
        pane.setItemMeta(pm);
        for (int i = ITEM_SLOTS; i < SIZE; i++) inv.setItem(i, pane);

        updateInfo(inv);
        p.openInventory(inv);
    }

    private void updateInfo(Inventory inv) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir()) items.add(it);
        }
        PriceManager.Quote q = plugin.prices().quote(items);

        ItemStack info = new ItemStack(Material.EMERALD);
        ItemMeta meta = info.getItemMeta();
        String total = DynaSell.money(q.total);
        meta.displayName(DynaSell.c(plugin.getConfig().getString("menu.info-name", "&aTotal: &2$%total%")
                .replace("%total%", total).replace("%items%", String.valueOf(q.items))));
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("menu.info-lore")) {
            lore.add(DynaSell.c(line.replace("%total%", total).replace("%items%", String.valueOf(q.items))));
        }
        meta.lore(lore);
        info.setItemMeta(meta);
        inv.setItem(INFO_SLOT, info);
    }

    private boolean isMenu(Inventory inv) {
        return inv != null && inv.getHolder(false) instanceof Holder;
    }

    private void scheduleUpdate(Inventory inv) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isMenu(inv)) updateInfo(inv);
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!isMenu(top)) return;
        int raw = e.getRawSlot();
        if (raw >= ITEM_SLOTS && raw < SIZE) {
            e.setCancelled(true);
            return;
        }
        if (e.getClick() == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
            return;
        }
        scheduleUpdate(top);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!isMenu(top)) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= ITEM_SLOTS && raw < SIZE) {
                e.setCancelled(true);
                return;
            }
        }
        scheduleUpdate(top);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (!isMenu(inv)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        plugin.sell(p, inv);
    }
}
