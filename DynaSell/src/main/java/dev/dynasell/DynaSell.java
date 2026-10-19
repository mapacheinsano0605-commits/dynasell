package dev.dynasell;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DynaSell extends JavaPlugin implements CommandExecutor, TabCompleter {

    private PriceManager prices;
    private SellMenu menu;

    // ------------------------------------------------------------------ utilidades

    public static Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s)
                .decoration(TextDecoration.ITALIC, false);
    }

    public static String money(double d) {
        return String.format(Locale.US, "%,.2f", d);
    }

    public PriceManager prices() {
        return prices;
    }

    private String msg(String key) {
        return getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + key, key);
    }

    /** Vault se consulta cuando hace falta: EssentialsX registra la economía después de cargar este plugin. */
    private Economy economy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    public void onEnable() {
        saveDefaultConfig();
        prices = new PriceManager(this);
        prices.load();

        menu = new SellMenu(this);
        getServer().getPluginManager().registerEvents(menu, this);

        for (String name : new String[]{"sell", "worth", "dynasell"}) {
            if (getCommand(name) != null) {
                getCommand(name).setExecutor(this);
                getCommand(name).setTabCompleter(this);
            }
        }

        // cada 5 minutos: recuperación de precios + variación + guardado
        getServer().getScheduler().runTaskTimer(this, () -> prices.tick(), 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        // cerrar menús abiertos para que se vendan / devuelvan los ítems
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof SellMenu.Holder) {
                p.closeInventory();
            }
        }
        if (prices != null) prices.save();
    }

    // ------------------------------------------------------------------ venta

    public void sell(Player p, Inventory inv) {
        List<ItemStack> toSell = new ArrayList<>();
        List<ItemStack> back = new ArrayList<>();
        for (int i = 0; i < SellMenu.ITEM_SLOTS; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            if (prices.basePrice(it.getType()) == null) back.add(it);
            else toSell.add(it);
        }
        if (toSell.isEmpty() && back.isEmpty()) {
            inv.clear();
            return;
        }

        boolean ok = false;
        PriceManager.Quote q = null;
        if (!toSell.isEmpty()) {
            q = prices.quote(toSell);
            Economy eco = economy();
            if (eco != null && q.total > 0) {
                EconomyResponse r = eco.depositPlayer(p, q.total);
                ok = r.transactionSuccess();
            }
        }

        if (ok) {
            prices.commit(q);
            String text = getConfig().getString("messages.sold", "")
                    .replace("%items%", String.valueOf(q.items))
                    .replace("%money%", money(q.total));
            p.sendMessage(c(getConfig().getString("messages.prefix", "") + text));
            p.sendActionBar(c(text));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        } else {
            back.addAll(toSell);
            p.sendMessage(c(msg(toSell.isEmpty() ? "nothing" : "error")));
        }

        if (!back.isEmpty()) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(back.toArray(new ItemStack[0]));
            for (ItemStack it : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), it);
            }
        }
        inv.clear();
    }

    // ------------------------------------------------------------------ comandos

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "sell" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(c(msg("only-players")));
                    return true;
                }
                menu.open(p);
            }
            case "worth" -> worth(sender, args);
            case "dynasell" -> admin(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void worth(CommandSender sender, String[] args) {
        Material m = null;
        int amount = 1;
        if (args.length > 0) {
            m = Material.matchMaterial(args[0]);
        } else if (sender instanceof Player p) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!hand.getType().isAir()) {
                m = hand.getType();
                amount = hand.getAmount();
            }
        } else {
            sender.sendMessage(c(msg("only-players")));
            return;
        }
        if (m == null || prices.basePrice(m) == null) {
            sender.sendMessage(c(msg("unknown-item")));
            return;
        }
        double base = prices.basePrice(m);
        double now = prices.unitPrice(m);
        double pct = (now / base - 1.0) * 100.0;
        sender.sendMessage(c(msg("worth")
                .replace("%item%", m.name().toLowerCase(Locale.ROOT))
                .replace("%price%", money(now))
                .replace("%base%", money(base))
                .replace("%percent%", String.format(Locale.US, "%+.1f", pct))));
        if (amount > 1) {
            sender.sendMessage(c(msg("worth-stack")
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%total%", money(now * amount))));
        }
    }

    private void admin(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(c(msg("usage-admin")));
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                prices.save();
                prices.load();
                sender.sendMessage(c(msg("reloaded")));
            }
            case "reset" -> {
                prices.resetMultipliers();
                prices.load();
                sender.sendMessage(c(msg("reset")));
            }
            case "setprice" -> {
                if (args.length < 3) {
                    sender.sendMessage(c(msg("usage-admin")));
                    return;
                }
                Material m = Material.matchMaterial(args[1]);
                if (m == null || !prices.sellable(m)) {
                    sender.sendMessage(c(msg("unknown-item")));
                    return;
                }
                double price;
                try {
                    price = Double.parseDouble(args[2]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(c(msg("usage-admin")));
                    return;
                }
                prices.setBasePrice(m, price);
                sender.sendMessage(c(msg("price-set")
                        .replace("%item%", m.name().toLowerCase(Locale.ROOT))
                        .replace("%price%", money(price))));
            }
            default -> sender.sendMessage(c(msg("usage-admin")));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("dynasell")) {
            if (args.length == 1) {
                for (String s : List.of("reload", "setprice", "reset")) {
                    if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
                }
                return out;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("setprice")) {
                return materials(args[1]);
            }
            return out;
        }
        if (name.equals("worth") && args.length == 1) return materials(args[0]);
        return out;
    }

    private List<String> materials(String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (Material m : prices.allSellable()) {
            String n = m.name().toLowerCase(Locale.ROOT);
            if (n.startsWith(p)) {
                out.add(n);
                if (out.size() >= 50) break;
            }
        }
        return out;
    }
}
