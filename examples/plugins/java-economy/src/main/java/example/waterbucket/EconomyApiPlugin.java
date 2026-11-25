/*
 * Copyright 2026 WaterbucketTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package example.waterbucket;

import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.CommandSettings;
import dev.waterbucket.waterbucketpe.plugin.Plugin;
import dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerJoinEvent;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The well-known economy plugin of the original server software, rebuilt on the proxy through
 * the compatibility surface only: one {@link Config} file for the balances, the join event for
 * automatic account creation and three commands. Nothing here touches proxy internals beyond
 * the documented compatibility API - it is the proof that economy-style plugins run on the
 * proxy (the same shape runs from Python and PHP with the same calls).
 */
public class EconomyApiPlugin extends Plugin {

    /**
     * The starting balance a fresh account receives, like the original plugin's default.
     */
    public static final double DEFAULT_MONEY = 1000.0D;

    private Config balances;
    private final Map<String, Double> cache = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        this.balances = new Config(java.nio.file.Path.of(
                this.getDataFolder().getAbsolutePath(), "balances.yml"));
        for (Map.Entry<String, Object> entry : this.balances.getAll().entrySet()) {
            if (entry.getValue() instanceof Number number) {
                this.cache.put(entry.getKey().toLowerCase(Locale.ROOT), number.doubleValue());
            }
        }

        // Every player gets an account on their first join, exactly like the original plugin.
        this.getProxy().getEventManager().subscribe(PlayerJoinEvent.class, event -> {
            Player player = event.getPlayer();
            if (!this.existsAccount(player.getName())) {
                this.createAccount(player.getName());
            }
        });

        this.getProxy().getCommandMap().registerCommand(new MoneyCommand(this));
        this.getProxy().getCommandMap().registerCommand(new PayCommand(this));
        this.getProxy().getCommandMap().registerCommand(new EcoCommand(this));
    }

    @Override
    public void onDisable() {
        this.saveAll();
    }

    // ---- the economy surface plugins of the original ecosystem expect ----

    public boolean existsAccount(String name) {
        return this.cache.containsKey(this.key(name));
    }

    public boolean createAccount(String name) {
        String key = this.key(name);
        if (this.cache.containsKey(key)) {
            return false;
        }
        this.cache.put(key, DEFAULT_MONEY);
        this.balances.set(key, DEFAULT_MONEY);
        this.balances.save();
        return true;
    }

    public boolean deleteAccount(String name) {
        String key = this.key(name);
        Double removed = this.cache.remove(key);
        if (removed == null) {
            return false;
        }
        this.balances.remove(key);
        this.balances.save();
        return true;
    }

    public double getMoney(String name) {
        Double money = this.cache.get(this.key(name));
        if (money == null) {
            this.createAccount(name);
            return DEFAULT_MONEY;
        }
        return money;
    }

    public boolean setMoney(String name, double amount) {
        if (amount < 0) {
            return false;
        }
        this.cache.put(this.key(name), amount);
        this.balances.set(this.key(name), amount);
        this.balances.save();
        return true;
    }

    public boolean addMoney(String name, double amount) {
        return this.setMoney(name, this.getMoney(name) + amount);
    }

    public boolean reduceMoney(String name, double amount) {
        double money = this.getMoney(name);
        if (money - amount < 0) {
            return false;
        }
        return this.setMoney(name, money - amount);
    }

    /**
     * Moves money between two accounts atomically over both failures: unknown accounts and
     * amounts that would make the balance negative are rejected as a whole.
     */
    public boolean pay(String from, String to, double amount) {
        if (amount <= 0 || from.equalsIgnoreCase(to)) {
            return false;
        }
        double fromMoney = this.getMoney(from);
        if (fromMoney - amount < 0) {
            return false;
        }
        if (!this.existsAccount(to)) {
            this.createAccount(to);
        }
        this.setMoney(from, fromMoney - amount);
        this.addMoney(to, amount);
        return true;
    }

    public Map<String, Double> getAllMoney() {
        return Map.copyOf(this.cache);
    }

    public void saveAll() {
        for (Map.Entry<String, Double> entry : this.cache.entrySet()) {
            this.balances.set(entry.getKey(), entry.getValue());
        }
        this.balances.save();
    }

    private String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    // ---- commands ----

    private static class MoneyCommand extends Command {

        private final EconomyApiPlugin economy;

        MoneyCommand(EconomyApiPlugin economy) {
            super("money", CommandSettings.builder()
                    .setDescription("Show your or another player's balance")
                    .setUsageMessage("/money [player]")
                    .build());
            this.economy = economy;
        }

        @Override
        public boolean onExecute(CommandSender sender, String alias, String[] args) {
            String target = args.length > 0 ? args[0] : sender.getName();
            if (!this.economy.existsAccount(target)) {
                sender.sendMessage("§cNo account for " + target);
                return true;
            }
            sender.sendMessage(String.format(Locale.ROOT, "§aBalance of %s: %.2f", target,
                    this.economy.getMoney(target)));
            return true;
        }
    }

    private static class PayCommand extends Command {

        private final EconomyApiPlugin economy;

        PayCommand(EconomyApiPlugin economy) {
            super("pay", CommandSettings.builder()
                    .setDescription("Send your money to another player")
                    .setUsageMessage("/pay <player> <amount>")
                    .build());
            this.economy = economy;
        }

        @Override
        public boolean onExecute(CommandSender sender, String alias, String[] args) {
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /pay <player> <amount>");
                return true;
            }
            double amount;
            try {
                amount = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cAmount must be a number");
                return true;
            }
            if (!sender.isPlayer()) {
                sender.sendMessage("§cOnly players can pay");
                return true;
            }
            boolean ok = this.economy.pay(sender.getName(), args[0], amount);
            sender.sendMessage(ok ? "§aSent " + amount + " to " + args[0]
                    : "§cPayment failed (unknown account, too little money or bad amount)");
            return true;
        }
    }

    private static class EcoCommand extends Command {

        private final EconomyApiPlugin economy;

        EcoCommand(EconomyApiPlugin economy) {
            super("eco", CommandSettings.builder()
                    .setDescription("Manage balances (admin)")
                    .setUsageMessage("/eco <set|add|reduce|create> <player> [amount]")
                    .setPermission("economy.admin")
                    .build());
            this.economy = economy;
        }

        @Override
        public boolean onExecute(CommandSender sender, String alias, String[] args) {
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /eco <set|add|reduce|create> <player> [amount]");
                return true;
            }
            String action = args[0].toLowerCase(Locale.ROOT);
            String target = args[1];
            switch (action) {
                case "create" -> {
                    sender.sendMessage(this.economy.createAccount(target)
                            ? "§aAccount created for " + target : "§cAccount already exists");
                    return true;
                }
                case "set", "add", "reduce" -> {
                    if (args.length < 3) {
                        sender.sendMessage("§c" + action + " needs an amount");
                        return true;
                    }
                    double amount;
                    try {
                        amount = Double.parseDouble(args[2]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§cAmount must be a number");
                        return true;
                    }
                    boolean ok = switch (action) {
                        case "set" -> this.economy.setMoney(target, amount);
                        case "add" -> this.economy.addMoney(target, amount);
                        default -> this.economy.reduceMoney(target, amount);
                    };
                    sender.sendMessage(ok
                            ? String.format(Locale.ROOT, "§a%s is now at %.2f", target,
                            this.economy.getMoney(target))
                            : "§cRejected (negative balance or negative amount)");
                    return true;
                }
                default -> {
                    sender.sendMessage("§cUnknown action " + action);
                    return true;
                }
            }
        }
    }
}
