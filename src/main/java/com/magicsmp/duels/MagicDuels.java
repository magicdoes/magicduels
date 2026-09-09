package com.magicsmp.duels;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.stream.Collectors;

public final class MagicDuels extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<UUID, DuelRequest> incomingRequests = new HashMap<>();
    private final Map<UUID, DuelSession> sessions = new HashMap<>();
    private final Map<UUID, PendingRespawn> pendingRespawns = new HashMap<>();
    private Economy economy;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        hookEconomy();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("duel")).setExecutor(this);
        Objects.requireNonNull(getCommand("duel")).setTabCompleter(this);
        getLogger().info("MagicDuels enabled. Economy hook: " + (economy != null));
    }

    @Override
    public void onDisable() {
        // Safely return everyone and refund wagers on plugin/server shutdown.
        Set<DuelSession> unique = new HashSet<>(sessions.values());
        for (DuelSession session : unique) {
            cancelSession(session, true, "Server/plugin shutdown");
        }
        sessions.clear();
        pendingRespawns.clear();
        incomingRequests.clear();
    }

    private void hookEconomy() {
        economy = null;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider != null) economy = provider.getProvider();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("magicduels.use")) {
            msg(player, "no-permission");
            return true;
        }

        cleanupExpiredRequests();

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("accept") || sub.equals("deny")) {
            if (args.length < 2) {
                player.sendMessage(color(prefix() + "&cUsage: /duel " + sub + " <player>"));
                return true;
            }
            Player challenger = Bukkit.getPlayerExact(args[1]);
            if (challenger == null) {
                msg(player, "player-not-found");
                return true;
            }
            DuelRequest request = incomingRequests.get(player.getUniqueId());
            if (request == null || !request.challenger.equals(challenger.getUniqueId()) || request.expired()) {
                msg(player, request != null && request.expired() ? "request-expired" : "no-request");
                incomingRequests.remove(player.getUniqueId());
                return true;
            }
            incomingRequests.remove(player.getUniqueId());
            if (sub.equals("deny")) {
                msg(player, "request-denied-target", "%player%", challenger.getName());
                msg(challenger, "request-denied-sender", "%player%", player.getName());
                return true;
            }
            startDuel(challenger, player, request.wager);
            return true;
        }

        if (sub.equals("cancel")) {
            DuelRequest request = incomingRequests.remove(player.getUniqueId());
            if (request != null) {
                Player challenger = Bukkit.getPlayer(request.challenger);
                msg(player, "duel-cancelled");
                if (challenger != null) msg(challenger, "duel-cancelled");
            } else {
                msg(player, "no-request");
            }
            return true;
        }

        if (sub.equals("setspawn") || sub.equals("reload")) {
            if (!player.hasPermission("magicduels.admin")) {
                msg(player, "no-permission");
                return true;
            }
            if (sub.equals("reload")) {
                reloadConfig();
                hookEconomy();
                msg(player, "reloaded");
                return true;
            }
            if (args.length < 2 || !(args[1].equals("1") || args[1].equals("2"))) {
                player.sendMessage(color(prefix() + "&cUsage: /duel setspawn <1|2>"));
                return true;
            }
            getConfig().set("arena.spawn" + args[1], player.getLocation());
            saveConfig();
            msg(player, "arena-spawn-set", "%number%", args[1]);
            return true;
        }

        if (sub.equals("status")) {
            DuelSession session = sessions.get(player.getUniqueId());
            if (session == null) {
                player.sendMessage(color(prefix() + "&7You are not currently in a duel."));
            } else {
                UUID opponentId = session.other(player.getUniqueId());
                Player opponent = Bukkit.getPlayer(opponentId);
                player.sendMessage(color(prefix() + "&7Dueling: &e" + (opponent == null ? "Unknown" : opponent.getName()) + "&7 | Wager: &a$" + formatMoney(session.wager)));
            }
            return true;
        }

        // /duel <player> [wager]
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            msg(player, "player-not-found");
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            msg(player, "cannot-duel-self");
            return true;
        }
        if (sessions.containsKey(player.getUniqueId()) || sessions.containsKey(target.getUniqueId())) {
            msg(player, "already-dueling");
            return true;
        }
        if (!arenaReady()) {
            msg(player, "arena-not-set");
            return true;
        }

        double wager = 0;
        if (args.length >= 2) {
            try {
                wager = Double.parseDouble(args[1]);
                if (!Double.isFinite(wager) || wager < 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                msg(player, "invalid-wager");
                return true;
            }
        }
        if (wager > 0) {
            if (!getConfig().getBoolean("wagers.enabled", true)) {
                msg(player, "wager-disabled");
                return true;
            }
            if (economy == null) {
                msg(player, "no-economy");
                return true;
            }
            double min = getConfig().getDouble("wagers.minimum", 0);
            double max = getConfig().getDouble("wagers.maximum", 1_000_000_000D);
            if (wager < min) {
                msg(player, "wager-too-small", "%amount%", formatMoney(min));
                return true;
            }
            if (wager > max) {
                msg(player, "wager-too-large", "%amount%", formatMoney(max));
                return true;
            }
            if (!economy.has(player, wager) || !economy.has(target, wager)) {
                msg(player, "not-enough-money");
                return true;
            }
        }

        int timeout = Math.max(10, getConfig().getInt("request-timeout-seconds", 60));
        incomingRequests.put(target.getUniqueId(), new DuelRequest(player.getUniqueId(), target.getUniqueId(), wager, System.currentTimeMillis() + timeout * 1000L));
        msg(player, "request-sent", "%player%", target.getName());
        String wagerText = wager > 0 ? " &7for &a$" + formatMoney(wager) : "";
        msg(target, "request-received", "%player%", player.getName(), "%wager%", wagerText);
        return true;
    }

    private void startDuel(Player a, Player b, double wager) {
        if (!arenaReady()) {
            msg(a, "arena-not-set");
            msg(b, "arena-not-set");
            return;
        }
        if (sessions.containsKey(a.getUniqueId()) || sessions.containsKey(b.getUniqueId())) {
            msg(a, "already-dueling");
            msg(b, "already-dueling");
            return;
        }
        if (wager > 0) {
            if (economy == null || !economy.has(a, wager) || !economy.has(b, wager)) {
                msg(a, economy == null ? "no-economy" : "not-enough-money");
                msg(b, economy == null ? "no-economy" : "not-enough-money");
                return;
            }
            if (!economy.withdrawPlayer(a, wager).transactionSuccess()) {
                msg(a, "duel-cancelled");
                msg(b, "duel-cancelled");
                return;
            }
            if (!economy.withdrawPlayer(b, wager).transactionSuccess()) {
                economy.depositPlayer(a, wager);
                msg(a, "duel-cancelled");
                msg(b, "duel-cancelled");
                return;
            }
        }

        DuelSession session = new DuelSession(a, b, wager);
        sessions.put(a.getUniqueId(), session);
        sessions.put(b.getUniqueId(), session);

        preparePlayerForArena(a, getConfig().getLocation("arena.spawn1"));
        preparePlayerForArena(b, getConfig().getLocation("arena.spawn2"));

        int countdown = Math.max(1, getConfig().getInt("countdown-seconds", 3));
        session.countdownTask = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            int left = countdown;
            @Override
            public void run() {
                if (session.ended) {
                    if (session.countdownTask != null) session.countdownTask.cancel();
                    return;
                }
                Player pa = Bukkit.getPlayer(session.a);
                Player pb = Bukkit.getPlayer(session.b);
                if (pa == null || pb == null) {
                    cancelSession(session, true, "Disconnected during countdown");
                    return;
                }
                if (left <= 0) {
                    session.active = true;
                    msg(pa, "fight");
                    msg(pb, "fight");
                    if (session.countdownTask != null) session.countdownTask.cancel();
                    return;
                }
                msg(pa, "countdown", "%seconds%", String.valueOf(left));
                msg(pb, "countdown", "%seconds%", String.valueOf(left));
                left--;
            }
        }, 0L, 20L);
    }

    private void preparePlayerForArena(Player p, Location location) {
        p.closeInventory();
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setVelocity(new Vector(0, 0, 0));
        double max = Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue();
        p.setHealth(max);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.teleport(Objects.requireNonNull(location));
    }

    private void finishWithWinner(DuelSession session, UUID winnerId, UUID loserId) {
        if (session.ended) return;
        session.ended = true;
        if (session.countdownTask != null) session.countdownTask.cancel();
        sessions.remove(session.a);
        sessions.remove(session.b);

        Player winner = Bukkit.getPlayer(winnerId);
        Player loser = Bukkit.getPlayer(loserId);

        InventorySnapshot winnerSnapshot = session.snapshot(winnerId);
        InventorySnapshot loserSnapshot = session.snapshot(loserId);

        if (winner != null) {
            winnerSnapshot.restore(winner);
            winner.teleport(winnerSnapshot.location);
            msg(winner, "winner", "%winner%", winner.getName(), "%loser%", loser == null ? "Opponent" : loser.getName());
        }
        if (loser != null && !loser.isDead()) {
            loserSnapshot.restore(loser);
            loser.teleport(loserSnapshot.location);
            msg(loser, "winner", "%winner%", winner == null ? "Opponent" : winner.getName(), "%loser%", loser.getName());
        }

        String winnerName = winner != null ? winner.getName() : Bukkit.getOfflinePlayer(winnerId).getName();
        String loserName = loser != null ? loser.getName() : Bukkit.getOfflinePlayer(loserId).getName();
        Bukkit.broadcastMessage(color(prefix() + message("winner")
                .replace("%winner%", winnerName == null ? "Winner" : winnerName)
                .replace("%loser%", loserName == null ? "Loser" : loserName)));

        if (session.wager > 0 && economy != null) {
            double prize = session.wager * 2D;
            economy.depositPlayer(Bukkit.getOfflinePlayer(winnerId), prize);
            Bukkit.broadcastMessage(color(prefix() + message("wager-winner")
                    .replace("%winner%", winnerName == null ? "Winner" : winnerName)
                    .replace("%amount%", formatMoney(prize))));
        }
    }

    private void cancelSession(DuelSession session, boolean refund, String reason) {
        if (session == null || session.ended) return;
        session.ended = true;
        if (session.countdownTask != null) session.countdownTask.cancel();
        sessions.remove(session.a);
        sessions.remove(session.b);

        for (UUID id : List.of(session.a, session.b)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                InventorySnapshot snap = session.snapshot(id);
                snap.restore(p);
                p.teleport(snap.location);
                msg(p, "duel-cancelled");
            }
        }
        if (refund && session.wager > 0 && economy != null) {
            economy.depositPlayer(Bukkit.getOfflinePlayer(session.a), session.wager);
            economy.depositPlayer(Bukkit.getOfflinePlayer(session.b), session.wager);
        }
        getLogger().info("Duel cancelled: " + reason);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player loser = event.getEntity();
        DuelSession session = sessions.get(loser.getUniqueId());
        if (session == null || session.ended) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        event.setKeepLevel(true);

        UUID winnerId = session.other(loser.getUniqueId());
        pendingRespawns.put(loser.getUniqueId(), new PendingRespawn(session.snapshot(loser.getUniqueId())));
        finishWithWinner(session, winnerId, loser.getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        PendingRespawn pending = pendingRespawns.remove(event.getPlayer().getUniqueId());
        if (pending == null) return;
        event.setRespawnLocation(pending.snapshot.location);
        Bukkit.getScheduler().runTask(this, () -> pending.snapshot.restore(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player victim = event.getEntity() instanceof Player p ? p : null;
        Player attacker = resolvePlayerDamager(event.getDamager());
        if (victim == null && attacker == null) return;

        DuelSession victimSession = victim == null ? null : sessions.get(victim.getUniqueId());
        DuelSession attackerSession = attacker == null ? null : sessions.get(attacker.getUniqueId());
        if (victimSession == null && attackerSession == null) return;

        // A duel participant can only damage their own opponent after countdown.
        if (victim == null || attacker == null || victimSession == null || victimSession != attackerSession || !victimSession.active) {
            event.setCancelled(true);
            return;
        }
        if (!victimSession.other(victim.getUniqueId()).equals(attacker.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private Player resolvePlayerDamager(Entity entity) {
        if (entity instanceof Player p) return p;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        DuelSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.active || session.ended || event.getTo() == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            event.setTo(from);
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!sessions.containsKey(event.getPlayer().getUniqueId())) return;
        String command = event.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        List<String> allowed = getConfig().getStringList("allowed-commands-during-duel").stream()
                .map(s -> s.toLowerCase(Locale.ROOT).replaceFirst("^/", ""))
                .collect(Collectors.toList());
        if (!allowed.contains(command)) {
            event.setCancelled(true);
            msg(event.getPlayer(), "commands-blocked");
        }
    }

    @EventHandler public void onBreak(BlockBreakEvent e) { if (sessions.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { if (sessions.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (sessions.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onPickup(PlayerPickupItemEvent e) { if (sessions.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p && sessions.containsKey(p.getUniqueId()) && event.getClickedInventory() != p.getInventory()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!sessions.containsKey(event.getPlayer().getUniqueId())) return;
        if (event.getClickedBlock() != null && event.getClickedBlock().getState() instanceof InventoryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player quitter = event.getPlayer();
        incomingRequests.remove(quitter.getUniqueId());
        incomingRequests.entrySet().removeIf(e -> e.getValue().challenger.equals(quitter.getUniqueId()));

        DuelSession session = sessions.get(quitter.getUniqueId());
        if (session == null || session.ended) return;
        UUID other = session.other(quitter.getUniqueId());
        if (session.active) {
            // Leaving an active fight is a forfeit.
            finishWithWinner(session, other, quitter.getUniqueId());
        } else {
            cancelSession(session, true, "Player left during countdown");
        }
    }

    private boolean arenaReady() {
        Location one = getConfig().getLocation("arena.spawn1");
        Location two = getConfig().getLocation("arena.spawn2");
        return one != null && two != null && one.getWorld() != null && two.getWorld() != null;
    }

    private void cleanupExpiredRequests() {
        incomingRequests.entrySet().removeIf(e -> e.getValue().expired());
    }

    private void sendHelp(Player p) {
        p.sendMessage(color("&6&lMagicDuels"));
        p.sendMessage(color("&e/duel <player> &7- Challenge a player"));
        p.sendMessage(color("&e/duel <player> <amount> &7- Challenge with a wager"));
        p.sendMessage(color("&e/duel accept <player>"));
        p.sendMessage(color("&e/duel deny <player>"));
        p.sendMessage(color("&e/duel status"));
        if (p.hasPermission("magicduels.admin")) {
            p.sendMessage(color("&c/duel setspawn <1|2>"));
            p.sendMessage(color("&c/duel reload"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("accept", "deny", "cancel", "status"));
            if (sender.hasPermission("magicduels.admin")) values.addAll(List.of("setspawn", "reload"));
            for (Player p : Bukkit.getOnlinePlayers()) values.add(p.getName());
            String start = args[0].toLowerCase(Locale.ROOT);
            return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(start)).distinct().sorted().toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setspawn") && sender.hasPermission("magicduels.admin")) {
            return List.of("1", "2");
        }
        return Collections.emptyList();
    }

    private String prefix() { return getConfig().getString("messages.prefix", "&6&lMagicSMP &8» &r"); }
    private String message(String key) { return getConfig().getString("messages." + key, "&cMissing message: " + key); }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private void msg(CommandSender sender, String key, String... replacements) {
        String text = message(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) text = text.replace(replacements[i], replacements[i + 1]);
        sender.sendMessage(color(prefix() + text));
    }

    private String formatMoney(double amount) {
        if (amount == Math.rint(amount)) return String.format(Locale.US, "%,.0f", amount);
        return String.format(Locale.US, "%,.2f", amount);
    }

    private static final class DuelRequest {
        private final UUID challenger;
        private final UUID target;
        private final double wager;
        private final long expiresAt;
        private DuelRequest(UUID challenger, UUID target, double wager, long expiresAt) {
            this.challenger = challenger;
            this.target = target;
            this.wager = wager;
            this.expiresAt = expiresAt;
        }
        private boolean expired() { return System.currentTimeMillis() > expiresAt; }
    }

    private static final class DuelSession {
        private final UUID a;
        private final UUID b;
        private final InventorySnapshot aSnapshot;
        private final InventorySnapshot bSnapshot;
        private final double wager;
        private boolean active = false;
        private boolean ended = false;
        private BukkitTask countdownTask;
        private DuelSession(Player a, Player b, double wager) {
            this.a = a.getUniqueId();
            this.b = b.getUniqueId();
            this.aSnapshot = InventorySnapshot.capture(a);
            this.bSnapshot = InventorySnapshot.capture(b);
            this.wager = wager;
        }
        private UUID other(UUID id) { return id.equals(a) ? b : a; }
        private InventorySnapshot snapshot(UUID id) { return id.equals(a) ? aSnapshot : bSnapshot; }
    }

    private static final class PendingRespawn {
        private final InventorySnapshot snapshot;
        private PendingRespawn(InventorySnapshot snapshot) { this.snapshot = snapshot; }
    }

    private static final class InventorySnapshot {
        private final ItemStack[] storage;
        private final ItemStack[] armor;
        private final ItemStack offhand;
        private final int level;
        private final float exp;
        private final int totalExp;
        private final int food;
        private final float saturation;
        private final double health;
        private final int fireTicks;
        private final GameMode gameMode;
        private final Location location;

        private InventorySnapshot(Player p) {
            this.storage = cloneItems(p.getInventory().getStorageContents());
            this.armor = cloneItems(p.getInventory().getArmorContents());
            this.offhand = cloneItem(p.getInventory().getItemInOffHand());
            this.level = p.getLevel();
            this.exp = p.getExp();
            this.totalExp = p.getTotalExperience();
            this.food = p.getFoodLevel();
            this.saturation = p.getSaturation();
            this.health = p.getHealth();
            this.fireTicks = p.getFireTicks();
            this.gameMode = p.getGameMode();
            this.location = p.getLocation().clone();
        }
        static InventorySnapshot capture(Player p) { return new InventorySnapshot(p); }
        void restore(Player p) {
            p.getInventory().setStorageContents(cloneItems(storage));
            p.getInventory().setArmorContents(cloneItems(armor));
            p.getInventory().setItemInOffHand(cloneItem(offhand));
            p.setLevel(level);
            p.setExp(exp);
            p.setTotalExperience(totalExp);
            p.setFoodLevel(food);
            p.setSaturation(saturation);
            p.setFireTicks(fireTicks);
            p.setGameMode(gameMode);
            double max = Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue();
            p.setHealth(Math.max(0.5D, Math.min(health, max)));
            p.setFallDistance(0);
            p.setVelocity(new Vector(0, 0, 0));
            p.updateInventory();
        }
        private static ItemStack[] cloneItems(ItemStack[] source) {
            ItemStack[] copy = new ItemStack[source.length];
            for (int i = 0; i < source.length; i++) copy[i] = cloneItem(source[i]);
            return copy;
        }
        private static ItemStack cloneItem(ItemStack item) {
            if (item == null || item.getType() == Material.AIR) return null;
            return item.clone();
        }
    }
}
