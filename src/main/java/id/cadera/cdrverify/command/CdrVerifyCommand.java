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
            "status", "reset", "unlink", "code", "force", "trusted", "revoke-trusted", "panel", "doctor", "reload"
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
            case "trusted" -> trusted(sender, args);
            case "revoke-trusted" -> revokeTrusted(sender, args);
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
        boolean directCode = plugin.getConfig().getBoolean("verification.discord.allow-message-code", true);
        boolean slash = plugin.getConfig().getBoolean("verification.discord.slash-command.enabled", false);
        boolean trusted = plugin.getConfig().getBoolean("security.ip-binding.trusted.enabled", true);

        sender.sendMessage(plugin.legacyComponent("&8&m--------------------------------"));
        sender.sendMessage(plugin.legacyComponent("&b&lCdrVerify Doctor"));
        sender.sendMessage(plugin.legacyComponent("&7Discord environment: " + (valid ? "&aVALID" : "&cINVALID")));
        sender.sendMessage(plugin.legacyComponent("&7Direct code message: " + (directCode ? "&aENABLED" : "&cDISABLED")));
        sender.sendMessage(plugin.legacyComponent("&7Slash /verify: " + (slash ? "&aENABLED" : "&cDISABLED")));
        sender.sendMessage(plugin.legacyComponent("&7Auto-delete submitted code: " +
                (plugin.getConfig().getBoolean("verification.discord.delete-submitted-code", true) ? "&aENABLED" : "&cDISABLED")));
        sender.sendMessage(plugin.legacyComponent("&7IP mode: &f" +
                plugin.getConfig().getString("security.ip-binding.mode", "STRICT")));
        sender.sendMessage(plugin.legacyComponent("&7Trusted IP: " + (trusted ? "&aENABLED" : "&cDISABLED")
                + " &8(max &f" + verificationService.trustedIpMaxEntries()
                + "&8, TTL &f" + verificationService.trustedIpTtlDays() + " hari&8)"));
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

        sender.sendMessage(plugin.legacyComponent("&7Trusted IPs: &f" + snapshot.trustedIps().size()
                + "&8/&f" + verificationService.trustedIpMaxEntries()));

        if (snapshot.lastIpHash() != null && !snapshot.lastIpHash().isBlank()) {
            String hash = snapshot.lastIpHash();
            sender.sendMessage(plugin.legacyComponent("&7Last IP fingerprint: &8" + shortHash(hash) + "..."));
        }
        sender.sendMessage(plugin.legacyComponent("&8&m--------------------------------"));
        return true;
    }

    private boolean trusted(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.legacyComponent("&cUsage: /cdrverify trusted <player|uuid>"));
            return true;
        }
        OfflinePlayer player = resolvePlayer(args[1]);
        if (player == null) {
            sendPlayerNotFound(sender, args[1]);
            return true;
        }

        String name = displayName(player, args[1]);
        List<VerificationService.TrustedIpSnapshot> entries = verificationService.trustedIps(player.getUniqueId());

        sender.sendMessage(plugin.legacyComponent("&8&m--------------------------------"));
        sender.sendMessage(plugin.legacyComponent("&b&lTrusted IPs &7- &f" + name));
        sender.sendMessage(plugin.legacyComponent("&7Slots: &f" + entries.size() + "&8/&f" + verificationService.trustedIpMaxEntries()
                + " &8| &7TTL: &f" + verificationService.trustedIpTtlDays() + " hari"));

        if (entries.isEmpty()) {
            sender.sendMessage(plugin.legacyComponent("&eBelum ada trusted IP aktif."));
        } else {
            long now = System.currentTimeMillis();
            for (int i = 0; i < entries.size(); i++) {
                VerificationService.TrustedIpSnapshot entry = entries.get(i);
                long ageMillis = Math.max(0L, now - entry.lastSeenAt());
                long ageDays = ageMillis / 86_400_000L;
                long ageHours = (ageMillis / 3_600_000L) % 24L;
                String age = ageDays > 0 ? ageDays + "hri " + ageHours + "j" : ageHours + "j";
                sender.sendMessage(plugin.legacyComponent("&7#" + (i + 1) + " &f" + shortHash(entry.fingerprint())
                        + "... &8- terakhir dipakai &f" + age + " &7lalu"));
            }
        }

        sender.sendMessage(plugin.legacyComponent("&8&m--------------------------------"));
        return true;
    }

    private boolean revokeTrusted(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.legacyComponent("&cUsage: /cdrverify revoke-trusted <player|uuid>"));
            return true;
        }
        OfflinePlayer player = resolvePlayer(args[1]);
        if (player == null) {
            sendPlayerNotFound(sender, args[1]);
            return true;
        }

        String name = displayName(player, args[1]);
        int removed = verificationService.revokeTrustedIps(player.getUniqueId());
        sender.sendMessage(plugin.legacyComponent("&8[&bCdrVerify&8] &aTrusted IP &f" + name
                + " &atelah dihapus. &8(&f" + removed + " IP&8)"));
        sender.sendMessage(plugin.legacyComponent("&7Login berikutnya pada mode STRICT akan meminta konfirmasi Discord lagi."));
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
                plugin.legacyComponent("&f/" + label + " trusted <player> &8- &7lihat trusted IP"),
                plugin.legacyComponent("&f/" + label + " revoke-trusted <player> &8- &7hapus semua trusted IP"),
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

    private String shortHash(String hash) {
        if (hash == null || hash.isBlank()) return "none";
        return hash.substring(0, Math.min(12, hash.length()));
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
