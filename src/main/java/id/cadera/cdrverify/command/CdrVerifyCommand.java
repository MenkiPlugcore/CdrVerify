package id.cadera.cdrverify.command;

import id.cadera.cdrverify.CdrVerifyPlugin;
import id.cadera.cdrverify.verification.VerificationService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class CdrVerifyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "status", "reset", "unlink", "code", "force", "panel", "doctor", "reload"
    );

    private final CdrVerifyPlugin plugin;
    private final VerificationService verificationService;

    public CdrVerifyCommand(CdrVerifyPlugin plugin, VerificationService verificationService) {
        this.plugin = plugin;
        this.verificationService = verificationService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("cdrverify.admin")) {
            sender.sendMessage(plugin.legacyComponent("&cKamu tidak memiliki permission cdrverify.admin."));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "status" -> status(sender, args);
            case "code" -> code(sender, args);
            case "reset" -> reset(sender, args);
            case "unlink" -> unlink(sender, args);
            case "force" -> force(sender, args);
            case "panel" -> panel(sender);
            case "doctor" -> doctor(sender);
            default -> {
                sendHelp(sender, label);
                yield true;
            }
        };
    }

    private boolean reload(CommandSender sender) {
        plugin.reloadPluginFiles();
        sender.sendMessage(plugin.component("minecraft.reloaded"));
        return true;
    }

    private boolean panel(CommandSender sender) {
        plugin.publishVerificationPanel(sender);
        return true;
    }

    private boolean doctor(CommandSender sender) {
        boolean valid = plugin.validateDiscordEnvironment();
        sender.sendMessage(plugin.legacyComponent("&8&m--------------------------------"));
        sender.sendMessage(plugin.legacyComponent("&b&lCdrVerify Doctor"));
        sender.sendMessage(plugin.legacyComponent("&7Discord environment: " + (valid ? "&aVALID" : "&cINVALID")));
        sender.sendMessage(plugin.legacyComponent("&7Slash /verify: " +
                (plugin.getConfig().getBoolean("verification.discord.slash-command.enabled", true) ? "&aENABLED" : "&cDISABLED")));
        sender.sendMessage(plugin.legacyComponent("&7Message-code fallback: " +
                (plugin.getConfig().getBoolean("verification.discord.allow-message-code", true) ? "&aENABLED" : "&cDISABLED")));
        sender.sendMessage(plugin.legacyComponent("&7IP mode: &f" +
                plugin.getConfig().getString("security.ip-binding.mode", "SESSION")));
        sender.sendMessage(plugin.legacyComponent("&7Code TTL: &f" +
                plugin.getConfig().getInt("verification.code.expire-minutes", 15) + " menit"));
        sender.sendMessage(plugin.legacyComponent("&8&m--------------------------------"));
        return true;
    }

    private boolean status(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.legacyComponent("&cUsage: /cdrverify status <player|uuid>"));
            return true;
        }
        OfflinePlayer player = resolvePlayer(args[1]);
        if (player == null) {
            sendPlayerNotFound(sender, args[1]);
            return true;
        }

        UUID uuid = player.getUniqueId();
        String name = displayName(player, args[1]);
        VerificationService.StatusSnapshot snapshot = verificationService.status(uuid, name);

        sender.sendMessage(plugin.legacyComponent("&8&m--------------------------------"));
        sender.sendMessage(plugin.legacyComponent("&b&lCdrVerify Account Status"));
        sender.sendMessage(plugin.legacyComponent("&7Player: &f" + name));
        sender.sendMessage(plugin.legacyComponent("&7UUID: &f" + uuid));
        sender.sendMessage(plugin.legacyComponent("&7Discord: " + (snapshot.discordId() == null
                ? "&cUNLINKED" : "&aLINKED &8(&f" + snapshot.discordId() + "&8)")));

        VerificationService.Session pending = snapshot.pending();
        if (pending == null) {
            sender.sendMessage(plugin.legacyComponent("&7Pending session: &aNONE"));
        } else {
            sender.sendMessage(plugin.legacyComponent("&7Pending session: &e" + pending.purpose().name()));
            sender.sendMessage(plugin.legacyComponent("&7Code: &f" + pending.code()));
            sender.sendMessage(plugin.legacyComponent("&7Expires: &f~" + verificationService.remainingMinutes(pending) + " menit"));
        }

        if (snapshot.lastIpHash() != null && !snapshot.lastIpHash().isBlank()) {
            String hash = snapshot.lastIpHash();
            sender.sendMessage(plugin.legacyComponent("&7IP fingerprint: &8" + hash.substring(0, Math.min(12, hash.length())) + "..."));
        }
        sender.sendMessage(plugin.legacyComponent("&8&m--------------------------------"));
        return true;
    }

    private boolean code(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.legacyComponent("&cUsage: /cdrverify code <player|uuid>"));
            return true;
        }
        OfflinePlayer player = resolvePlayer(args[1]);
        if (player == null) {
            sendPlayerNotFound(sender, args[1]);
            return true;
        }
        VerificationService.Session session = verificationService.getSession(player.getUniqueId()).orElse(null);
        if (session == null) {
            sender.sendMessage(plugin.component("minecraft.no-pending", Map.of("player", displayName(player, args[1]))));
            return true;
        }
        sender.sendMessage(plugin.legacyComponent("&8[&bCdrVerify&8] &7Code &f" + displayName(player, args[1])
                + "&7: &b&l" + session.code() + " &8(~" + verificationService.remainingMinutes(session) + "m)"));
        return true;
    }

    private boolean reset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.legacyComponent("&cUsage: /cdrverify reset <player|uuid>"));
            return true;
        }
        OfflinePlayer player = resolvePlayer(args[1]);
        if (player == null) {
            sendPlayerNotFound(sender, args[1]);
            return true;
        }
        verificationService.resetSession(player.getUniqueId());
        sender.sendMessage(plugin.component("minecraft.reset", Map.of("player", displayName(player, args[1]))));
        return true;
    }

    private boolean unlink(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.legacyComponent("&cUsage: /cdrverify unlink <player|uuid>"));
            return true;
        }
        OfflinePlayer player = resolvePlayer(args[1]);
        if (player == null) {
            sendPlayerNotFound(sender, args[1]);
            return true;
        }
        verificationService.unlink(player.getUniqueId());
        sender.sendMessage(plugin.component("minecraft.unlinked", Map.of("player", displayName(player, args[1]))));
        return true;
    }

    private boolean force(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.legacyComponent("&cUsage: /cdrverify force <player|uuid> <discord-id>"));
            return true;
        }
        if (!args[2].matches("\\d{15,25}")) {
            sender.sendMessage(plugin.legacyComponent("&cDiscord ID tidak valid. Gunakan numeric User ID Discord."));
            return true;
        }
        OfflinePlayer player = resolvePlayer(args[1]);
        if (player == null) {
            sendPlayerNotFound(sender, args[1]);
            return true;
        }
        String name = displayName(player, args[1]);
        boolean linked = verificationService.forceLink(player.getUniqueId(), name, args[2]);
        if (!linked) {
            sender.sendMessage(plugin.component("minecraft.force-conflict"));
            return true;
        }
        sender.sendMessage(plugin.component("minecraft.force-success", Map.of("player", name, "discord_id", args[2])));
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        List<Component> lines = List.of(
                plugin.legacyComponent("&8&m--------------------------------"),
                plugin.legacyComponent("&b&lCdrVerify &7Admin Commands"),
                plugin.legacyComponent("&f/" + label + " status <player> &8- &7lihat status"),
                plugin.legacyComponent("&f/" + label + " code <player> &8- &7lihat pending code"),
                plugin.legacyComponent("&f/" + label + " reset <player> &8- &7hapus sesi pending"),
                plugin.legacyComponent("&f/" + label + " unlink <player> &8- &7lepas link Discord"),
                plugin.legacyComponent("&f/" + label + " force <player> <discord-id> &8- &7force link aman"),
                plugin.legacyComponent("&f/" + label + " panel &8- &7kirim panel verifikasi Discord"),
                plugin.legacyComponent("&f/" + label + " doctor &8- &7cek kesiapan Discord/config"),
                plugin.legacyComponent("&f/" + label + " reload &8- &7reload config/messages"),
                plugin.legacyComponent("&8&m--------------------------------")
        );
        lines.forEach(sender::sendMessage);
    }

    private void sendPlayerNotFound(CommandSender sender, String input) {
        sender.sendMessage(plugin.component("minecraft.player-not-found", Map.of("player", input)));
    }

    private @Nullable OfflinePlayer resolvePlayer(String input) {
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(input));
        } catch (IllegalArgumentException ignored) {
        }
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) return online;
        return Arrays.stream(Bukkit.getOfflinePlayers())
                .filter(candidate -> candidate.getName() != null && candidate.getName().equalsIgnoreCase(input))
                .findFirst().orElse(null);
    }

    private String displayName(OfflinePlayer player, String fallback) {
        return player.getName() == null ? fallback : player.getName();
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("cdrverify.admin")) return Collections.emptyList();
        if (args.length == 1) return filterPrefix(SUBCOMMANDS, args[0]);
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload")
                && !args[0].equalsIgnoreCase("panel") && !args[0].equalsIgnoreCase("doctor")) {
            List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toCollection(ArrayList::new));
            return filterPrefix(players, args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filterPrefix(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).collect(Collectors.toList());
    }
}
