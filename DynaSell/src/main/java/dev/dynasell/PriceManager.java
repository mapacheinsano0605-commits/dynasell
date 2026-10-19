package dev.dynasell;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Precios base (prices.yml + calculados por receta) y multiplicador dinámico por ítem.
 */
public class PriceManager {

    /** Resultado de valorar una lista de ítems (sin cobrar todavía). */
    public static class Quote {
        /** material -> {cantidad real, cantidad ponderada por durabilidad} */
        final Map<Material, double[]> data = new LinkedHashMap<>();
        /** material -> multiplicador final tras la venta */
        final Map<Material, Double> finals = new EnumMap<>(Material.class);
        public int items;
        public double total;
    }

    private final DynaSell plugin;

    private final Map<Material, Double> base = new EnumMap<>(Material.class);
    private final Map<Material, Double> cache = new EnumMap<>(Material.class);
    private final Map<Material, List<Recipe>> recipes = new EnumMap<>(Material.class);
    private final Map<Material, Double> mult = new EnumMap<>(Material.class);
    private final Set<Material> computing = EnumSet.noneOf(Material.class);
    private final Set<Material> blacklist = EnumSet.noneOf(Material.class);
    private final List<Material> all = new ArrayList<>();

    private boolean dynamic = true;
    private double impact = 0.0003;
    private double minMult = 0.4;
    private double maxMult = 1.5;
    private double recoveryPerHour = 0.10;
    private double drift = 0.01;
    private double defaultPrice = 4.0;
    private double craftedMult = 0.9;
    private double smeltBonus = 1.0;

    public PriceManager(DynaSell plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ carga

    public void load() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();
        dynamic = c.getBoolean("dynamic.enabled", true);
        impact = c.getDouble("dynamic.impact-per-item", 0.0003);
        minMult = c.getDouble("dynamic.min-multiplier", 0.4);
        maxMult = c.getDouble("dynamic.max-multiplier", 1.5);
        recoveryPerHour = c.getDouble("dynamic.recovery-per-hour", 0.10);
        drift = c.getDouble("dynamic.drift", 0.01);
        defaultPrice = c.getDouble("default-price", 4.0);
        craftedMult = c.getDouble("crafted-multiplier", 0.9);
        smeltBonus = c.getDouble("smelting-bonus", 1.0);

        blacklist.clear();
        for (String s : c.getStringList("blacklist")) {
            Material m = Material.matchMaterial(s);
            if (m != null) blacklist.add(m);
        }

        File f = new File(plugin.getDataFolder(), "prices.yml");
        if (!f.exists()) plugin.saveResource("prices.yml", false);
        base.clear();
        cache.clear();
        ConfigurationSection sec = YamlConfiguration.loadConfiguration(f).getConfigurationSection("prices");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m == null) {
                    plugin.getLogger().warning("prices.yml: ítem desconocido '" + k + "' (se ignora)");
                    continue;
                }
                base.put(m, sec.getDouble(k));
            }
        }

        indexRecipes();

        all.clear();
        for (Material m : Material.values()) {
            if (sellable(m)) all.add(m);
        }
        loadData();
        plugin.getLogger().info("Ítems vendibles: " + all.size() + " (con precio fijo en prices.yml: " + base.size() + ")");
    }

    private void indexRecipes() {
        recipes.clear();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r;
            try {
                r = it.next();
            } catch (Exception ex) {
                continue;
            }
            if (!(r instanceof ShapedRecipe || r instanceof ShapelessRecipe
                    || r instanceof CookingRecipe<?> || r instanceof SmithingTransformRecipe)) continue;
            Material res = r.getResult().getType();
            if (res.isAir()) continue;
            recipes.computeIfAbsent(res, k -> new ArrayList<>()).add(r);
        }
    }

    // ------------------------------------------------------------------ datos dinámicos

    private File dataFile() {
        return new File(plugin.getDataFolder(), "data.yml");
    }

    private void loadData() {
        mult.clear();
        File f = dataFile();
        if (!f.exists()) return;
        ConfigurationSection sec = YamlConfiguration.loadConfiguration(f).getConfigurationSection("mult");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m != null) mult.put(m, sec.getDouble(k, 1.0));
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<Material, Double> e : mult.entrySet()) {
            y.set("mult." + e.getKey().name(), Math.round(e.getValue() * 10000.0) / 10000.0);
        }
        try {
            y.save(dataFile());
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo guardar data.yml: " + ex.getMessage());
        }
    }

    public void resetMultipliers() {
        mult.clear();
        save();
    }

    /** Se ejecuta cada 5 minutos: recuperación hacia el precio base + variación aleatoria. */
    public void tick() {
        if (!dynamic) return;
        double rec = 1 - Math.exp(-recoveryPerHour * (5.0 / 60.0));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Material m : all) {
            double v = mult.getOrDefault(m, 1.0);
            v += (1.0 - v) * rec;
            if (drift > 0) v *= 1 + (rnd.nextDouble() * 2 - 1) * drift;
            v = Math.max(minMult, Math.min(maxMult, v));
            mult.put(m, v);
        }
        save();
    }

    // ------------------------------------------------------------------ precios

    public boolean sellable(Material m) {
        if (m == null || m.isAir() || !m.isItem() || m.isLegacy()) return false;
        return !blacklist.contains(m);
    }

    /** Precio base (sin dinámica) o null si el ítem no se puede vender. */
    public Double basePrice(Material m) {
        if (!sellable(m)) return null;
        Double c = cache.get(m);
        if (c != null) return c;

        Double v = base.get(m);
        if (v == null) {
            if (!computing.add(m)) return null; // ciclo de recetas
            try {
                v = fromRecipes(m);
            } finally {
                computing.remove(m);
            }
            if (v == null) v = defaultPrice;
        }
        v = Math.max(0.01, v);
        cache.put(m, v);
        return v;
    }

    public double multiplier(Material m) {
        return dynamic ? mult.getOrDefault(m, 1.0) : 1.0;
    }

    /** Precio actual por unidad (base x multiplicador). */
    public double unitPrice(Material m) {
        Double b = basePrice(m);
        return b == null ? 0 : b * multiplier(m);
    }

    private Double fromRecipes(Material m) {
        List<Recipe> list = recipes.get(m);
        if (list == null) return null;
        Double best = null;
        for (Recipe r : list) {
            Double cost = recipeCost(r);
            if (cost == null) continue;
            double unit = cost / Math.max(1, r.getResult().getAmount());
            if (best == null || unit < best) best = unit;
        }
        if (best == null) return null;
        return Math.max(0.01, Math.round(best * craftedMult * 100.0) / 100.0);
    }

    private Double recipeCost(Recipe r) {
        double total = 0;
        if (r instanceof ShapedRecipe s) {
            Map<Character, RecipeChoice> map = s.getChoiceMap();
            for (String row : s.getShape()) {
                for (char ch : row.toCharArray()) {
                    if (ch == ' ') continue;
                    RecipeChoice choice = map.get(ch);
                    if (choice == null) continue;
                    Double p = choicePrice(choice);
                    if (p == null) return null;
                    total += p;
                }
            }
            return total;
        }
        if (r instanceof ShapelessRecipe s) {
            for (RecipeChoice choice : s.getChoiceList()) {
                Double p = choicePrice(choice);
                if (p == null) return null;
                total += p;
            }
            return total;
        }
        if (r instanceof CookingRecipe<?> c) {
            Double p = choicePrice(c.getInputChoice());
            return p == null ? null : p + smeltBonus;
        }
        if (r instanceof SmithingTransformRecipe s) {
            RecipeChoice[] parts = {s.getTemplate(), s.getBase(), s.getAddition()};
            for (RecipeChoice choice : parts) {
                if (choice == null) continue;
                Double p = choicePrice(choice);
                if (p == null) return null;
                total += p;
            }
            return total;
        }
        return null;
    }

    private Double choicePrice(RecipeChoice choice) {
        if (choice == null) return 0.0;
        Double best = null;
        if (choice instanceof RecipeChoice.MaterialChoice mc) {
            for (Material mat : mc.getChoices()) {
                Double p = basePrice(mat);
                if (p != null && (best == null || p < best)) best = p;
            }
        } else if (choice instanceof RecipeChoice.ExactChoice ec) {
            for (ItemStack it : ec.getChoices()) {
                Double p = basePrice(it.getType());
                if (p != null && (best == null || p < best)) best = p;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ venta

    /** Valora los ítems sin cobrar ni modificar precios. */
    public Quote quote(List<ItemStack> items) {
        Quote q = new Quote();
        for (ItemStack it : items) addToQuote(q, it);
        for (Map.Entry<Material, double[]> e : q.data.entrySet()) {
            Material m = e.getKey();
            double[] d = e.getValue();
            double[] sw = sweep(multiplier(m), d[0]);
            q.total += basePrice(m) * sw[0] * d[1];
            q.finals.put(m, sw[1]);
        }
        q.total = Math.round(q.total * 100.0) / 100.0;
        return q;
    }

    /** Aplica la bajada de precio de una venta ya cobrada. */
    public void commit(Quote q) {
        if (!dynamic) return;
        for (Map.Entry<Material, Double> e : q.finals.entrySet()) {
            mult.put(e.getKey(), e.getValue());
        }
    }

    private void addToQuote(Quote q, ItemStack it) {
        if (it == null || it.getType().isAir()) return;
        Material m = it.getType();
        if (basePrice(m) == null) return;

        double factor = 1.0;
        if (it.getItemMeta() instanceof Damageable d && d.hasDamage()) {
            int max = m.getMaxDurability();
            if (max > 0) factor = Math.max(0.05, 1.0 - d.getDamage() / (double) max);
        }
        double[] arr = q.data.computeIfAbsent(m, k -> new double[2]);
        arr[0] += it.getAmount();
        arr[1] += it.getAmount() * factor;
        q.items += it.getAmount();

        // contenido de shulker boxes
        if (it.getItemMeta() instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox sb) {
            for (ItemStack inner : sb.getInventory().getContents()) addToQuote(q, inner);
        }
    }

    /**
     * Vender n unidades empezando en el multiplicador m0.
     * Devuelve {multiplicador promedio pagado, multiplicador final}.
     */
    private double[] sweep(double m0, double n) {
        if (!dynamic || impact <= 0 || n <= 0) return new double[]{m0, m0};
        if (m0 <= minMult) return new double[]{m0, m0};
        double r = 1 - impact;
        double negLn = -Math.log(r);
        double kStar = Math.log(minMult / m0) / Math.log(r); // unidades hasta tocar el mínimo
        double sum;
        double fin;
        if (n <= kStar) {
            double rn = Math.pow(r, n);
            sum = m0 * (1 - rn) / negLn;
            fin = m0 * rn;
        } else {
            sum = (m0 - minMult) / negLn + (n - kStar) * minMult;
            fin = minMult;
        }
        return new double[]{sum / n, fin};
    }

    // ------------------------------------------------------------------ admin

    public void setBasePrice(Material m, double price) {
        File f = new File(plugin.getDataFolder(), "prices.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        y.set("prices." + m.name().toLowerCase(), price);
        try {
            y.save(f);
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo guardar prices.yml: " + ex.getMessage());
        }
        base.put(m, price);
        cache.clear();
    }

    public List<Material> allSellable() {
        return all;
    }
}
