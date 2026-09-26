package id.cadera.cdrverify.listener;

import github.scarsz.discordsrv.api.ListenerPriority;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordGuildMessageReceivedEvent;
import id.cadera.cdrverify.CdrVerifyPlugin;
import id.cadera.cdrverify.verification.VerificationService;

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
        if (!plugin.getConfig().getBoolean("verification.discord.allow-message-code", false)) {
            return;
        }
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

        String reply = plugin.discordResultMessage(result, event.getAuthor().getName());
        int deleteSeconds = Math.max(0, plugin.getConfig().getInt("verification.discord.response-delete-seconds", 15));

        event.getChannel().sendMessage(reply).queue(message -> {
            if (deleteSeconds > 0) {
                message.delete().queueAfter(deleteSeconds, TimeUnit.SECONDS, ignored -> { }, failure -> { });
            }
        }, failure -> plugin.getLogger().warning("Gagal mengirim respon verifikasi Discord: " + failure.getMessage()));

        if (result.type() == VerificationService.ResultType.SUCCESS
                || result.type() == VerificationService.ResultType.REVERIFY_SUCCESS) {
            plugin.grantVerifiedRole(event.getGuild(), event.getMember());
            plugin.sendSuccessDm(event.getAuthor(), reply);
        }
    }
}
