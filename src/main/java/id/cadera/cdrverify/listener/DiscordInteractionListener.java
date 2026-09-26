package id.cadera.cdrverify.listener;

import github.scarsz.discordsrv.dependencies.jda.api.events.interaction.ButtonClickEvent;
import github.scarsz.discordsrv.dependencies.jda.api.hooks.ListenerAdapter;
import id.cadera.cdrverify.CdrVerifyPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DiscordInteractionListener extends ListenerAdapter {

    public static final String VERIFY_BUTTON_ID = "cdrverify:verify";

    private final CdrVerifyPlugin plugin;
    private final Map<String, Long> buttonCooldown = new ConcurrentHashMap<>();

    public DiscordInteractionListener(CdrVerifyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onButtonClick(@NotNull ButtonClickEvent event) {
        if (!VERIFY_BUTTON_ID.equals(event.getComponentId()) || event.getUser().isBot()) {
            return;
        }

        String configuredGuild = plugin.getConfig().getString("verification.discord.guild-id", "").trim();
        String configuredChannel = plugin.getConfig().getString("verification.discord.verification-channel-id", "").trim();

        if (event.getGuild() == null
                || (!configuredGuild.isEmpty() && !configuredGuild.equals(event.getGuild().getId()))
                || (!configuredChannel.isEmpty() && !configuredChannel.equals(event.getChannel().getId()))) {
            event.deferReply(true).setContent(plugin.message("discord.wrong-channel")).queue();
            return;
        }

        int cooldownSeconds = Math.max(1, plugin.getConfig().getInt("verification.discord.panel.button-cooldown-seconds", 3));
        long now = System.currentTimeMillis();
        long until = buttonCooldown.getOrDefault(event.getUser().getId(), 0L);
        if (until > now) {
            long seconds = Math.max(1L, (until - now + 999L) / 1000L);
            event.deferReply(true)
                    .setContent(plugin.message("discord.button-cooldown", Map.of("seconds", Long.toString(seconds))))
                    .queue();
            return;
        }

        buttonCooldown.put(event.getUser().getId(), now + cooldownSeconds * 1000L);
        event.deferReply(true).setContent(plugin.message("discord.button-help-v3")).queue();
    }
}
