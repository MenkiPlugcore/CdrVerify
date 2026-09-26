package id.cadera.cdrverify.listener;

import github.scarsz.discordsrv.api.ListenerPriority;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordGuildMessageReceivedEvent;
import id.cadera.cdrverify.CdrVerifyPlugin;
import id.cadera.cdrverify.verification.VerificationService;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class DiscordVerificationListener {

    private final CdrVerifyPlugin plugin;
    private final VerificationService verificationService;

    public DiscordVerificationListener(CdrVerifyPlugin plugin, VerificationService verificationService) {
        this.plugin = plugin;
        this.verificationService = verificationService;
    }

    @Subscribe(priority = ListenerPriority.MONITOR)
    public void onDiscordMessage(DiscordGuildMessageReceivedEvent event) {
        if (event.getAuthor() == null || event.getAuthor().isBot()) {
            return;
        }

        if (!verificationService.isVerificationChannel(event.getGuild().getId(), event.getChannel().getId())) {
            return;
        }

        String candidate = verificationService.extractCodeCandidate(event.getMessage().getContentRaw());
        if (candidate == null) {
            return;
        }

        if (plugin.getConfig().getBoolean("verification.discord.delete-submitted-code", true)) {
            event.getMessage().delete().queue(ignored -> { }, failure -> { });
        }

        VerificationService.DiscordResult result = verificationService.verifyFromDiscord(
                candidate,
                event.getAuthor().getId()
        );

        String path = switch (result.type()) {
            case SUCCESS -> "discord.success";
            case REVERIFY_SUCCESS -> "discord.reverify-success";
            case INVALID_CODE -> "discord.invalid-code";
            case LOCKED -> "discord.locked";
            case DISCORD_ALREADY_LINKED -> "discord.discord-already-linked";
            case MINECRAFT_ALREADY_LINKED -> "discord.minecraft-already-linked";
            case WRONG_DISCORD -> "discord.wrong-discord";
            case INTERNAL_ERROR -> "discord.internal-error";
        };

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", result.playerName() == null ? "Unknown" : result.playerName());
        placeholders.put("minutes", Long.toString(Math.max(
                1L,
                (result.remainingLockMillis() + 59_999L) / 60_000L
        )));

        String reply = plugin.message(path, placeholders);
        int deleteSeconds = Math.max(0, plugin.getConfig().getInt("verification.discord.response-delete-seconds", 15));

        event.getChannel().sendMessage(reply).queue(message -> {
            if (deleteSeconds > 0) {
                message.delete().queueAfter(deleteSeconds, TimeUnit.SECONDS, ignored -> { }, failure -> { });
            }
        }, failure -> plugin.getLogger().warning("Gagal mengirim respon verifikasi Discord: " + failure.getMessage()));

        if (result.type() == VerificationService.ResultType.SUCCESS
                || result.type() == VerificationService.ResultType.REVERIFY_SUCCESS) {
            grantVerifiedRole(event);
            sendSuccessDm(event, reply);
        }
    }

    private void grantVerifiedRole(DiscordGuildMessageReceivedEvent event) {
        String roleId = plugin.getConfig().getString("verification.discord.verified-role-id", "").trim();
        if (roleId.isEmpty() || event.getMember() == null) {
            return;
        }

        var role = event.getGuild().getRoleById(roleId);
        if (role == null) {
            plugin.getLogger().warning("Verified role Discord tidak ditemukan: " + roleId);
            return;
        }

        event.getGuild().addRoleToMember(event.getMember(), role).queue(
                ignored -> { },
                failure -> plugin.getLogger().warning("Gagal memberi verified role Discord: " + failure.getMessage())
        );
    }

    private void sendSuccessDm(DiscordGuildMessageReceivedEvent event, String reply) {
        if (!plugin.getConfig().getBoolean("verification.discord.dm-success", true)) {
            return;
        }

        event.getAuthor().openPrivateChannel().queue(channel ->
                channel.sendMessage(reply).queue(ignored -> { }, failure -> { })
        , failure -> { });
    }
}
