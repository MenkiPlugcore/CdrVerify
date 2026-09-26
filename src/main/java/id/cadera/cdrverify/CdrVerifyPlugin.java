package id.cadera.cdrverify;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.commands.PluginSlashCommand;
import github.scarsz.discordsrv.api.commands.SlashCommand;
import github.scarsz.discordsrv.api.commands.SlashCommandProvider;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Guild;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Member;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Role;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.dependencies.jda.api.events.interaction.SlashCommandEvent;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.commands.OptionType;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.commands.build.CommandData;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.components.Button;
import id.cadera.cdrverify.command.CdrVerifyCommand;
import id.cadera.cdrverify.listener.DiscordInteractionListener;
import id.cadera.cdrverify.listener.DiscordVerificationListener;
import id.cadera.cdrverify.listener.JoinGuardListener;
import id.cadera.cdrverify.verification.VerificationService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class CdrVerifyPlugin extends JavaPlugin implements SlashCommandProvider {

    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private VerificationService verificationService;
    private DiscordVerificationListener discordMessageListener;
    private DiscordInteractionListener discordInteractionListener;
    private YamlConfiguration messages;
    private volatile boolean discordEnvironmentReady;
    private volatile boolean jdaListenerRegistered;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveBundledMessages();
        reloadMessages();

        if (Bukkit.getPluginManager().getPlugin("DiscordSRV") == null) {
            getLogger().severe("DiscordSRV tidak ditemukan. CdrVerify membutuhkan DiscordSRV.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        verificationService = new VerificationService(this);
        verificationService.load();

        Bukkit.getPluginManager().registerEvents(new JoinGuardListener(this, verificationService), this);

        discordMessageListener = new DiscordVerificationListener(this, verificationService);
        DiscordSRV.api.subscribe(discordMessageListener);

        discordInteractionListener = new DiscordInteractionListener(this);
        registerJdaListenerWhenReady();

        CdrVerifyCommand command = new CdrVerifyCommand(this, verificationService);
        if (getCommand("cdrverify") != null) {
            getCommand("cdrverify").setExecutor(command);
            getCommand("cdrverify").setTabCompleter(command);
        }

        scheduleStorageCheckpoint();
        validateStaticConfiguration();

        getLogger().info("CdrVerify v" + getDescription().getVersion() + " enabled.");
        getLogger().info("Verification flow: Minecraft join -> Discord /verify -> rejoin.");
    }

    @Override
    public void onDisable() {
        if (discordMessageListener != null) {
            try {
                DiscordSRV.api.unsubscribe(discordMessageListener);
            } catch (Throwable ignored) {
            }
        }

        if (discordInteractionListener != null && jdaListenerRegistered) {
            try {
                var jda = DiscordSRV.getPlugin().getJda();
                if (jda != null) {
                    jda.removeEventListener(discordInteractionListener);
                }
            } catch (Throwable ignored) {
            }
        }

        if (verificationService != null) {
            verificationService.saveAll();
        }
    }

    public void reloadPluginFiles() {
        reloadConfig();
        reloadMessages();
        if (verificationService != null) {
            verificationService.reloadSettings();
        }
        validateStaticConfiguration();
        validateDiscordEnvironment();

        try {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    DiscordSRV.api.updateSlashCommands();
                } catch (Throwable throwable) {
                    getLogger().warning("Gagal refresh Discord slash commands: " + throwable.getMessage());
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void registerJdaListenerWhenReady() {
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            try {
                var jda = DiscordSRV.getPlugin().getJda();
                if (jda == null) {
                    return;
                }

                if (!jdaListenerRegistered) {
                    jda.addEventListener(discordInteractionListener);
                    jdaListenerRegistered = true;
                }

                validateDiscordEnvironment();
                task.cancel();
            } catch (Throwable throwable) {
                getLogger().warning("Menunggu DiscordSRV/JDA siap: " + throwable.getMessage());
            }
        }, 20L, 40L);
    }

    private void scheduleStorageCheckpoint() {
        int seconds = Math.max(30, getConfig().getInt("maintenance.checkpoint-interval-seconds", 60));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (verificationService != null) {
                verificationService.saveAll();
            }
        }, seconds * 20L, seconds * 20L);
    }

    private void saveBundledMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) {
            saveResource("messages.yml", false);
        }
    }

    private void reloadMessages() {
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
    }

    private void validateStaticConfiguration() {
        String guildId = getConfig().getString("verification.discord.guild-id", "").trim();
        String channelId = getConfig().getString("verification.discord.verification-channel-id", "").trim();

        if (guildId.isEmpty()) {
            getLogger().warning("verification.discord.guild-id belum diisi.");
        }
        if (channelId.isEmpty()) {
            getLogger().warning("verification.discord.verification-channel-id belum diisi.");
        }

        String salt = getConfig().getString("security.ip-binding.hash-salt", "");
        if (salt.isBlank() || "CHANGE-ME-CdrVerify".equals(salt)) {
            getLogger().warning("Ganti security.ip-binding.hash-salt sebelum production.");
        }

        String mode = getConfig().getString("security.ip-binding.mode", "SESSION").toUpperCase();
        if (!mode.equals("OFF") && !mode.equals("SESSION") && !mode.equals("STRICT")) {
            getLogger().warning("security.ip-binding.mode tidak valid: " + mode + ". Fallback: SESSION.");
        }
    }

    public boolean validateDiscordEnvironment() {
        discordEnvironmentReady = false;

        try {
            var jda = DiscordSRV.getPlugin().getJda();
            if (jda == null) {
                getLogger().warning("DiscordSRV JDA belum siap.");
                return false;
            }

            String guildId = getConfig().getString("verification.discord.guild-id", "").trim();
            String channelId = getConfig().getString("verification.discord.verification-channel-id", "").trim();
            String roleId = getConfig().getString("verification.discord.verified-role-id", "").trim();

            if (guildId.isEmpty() || channelId.isEmpty()) {
                return false;
            }

            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                getLogger().severe("Discord guild tidak ditemukan: " + guildId);
                return false;
            }

            TextChannel channel = guild.getTextChannelById(channelId);
            if (channel == null) {
                getLogger().severe("Verification channel tidak ditemukan di guild: " + channelId);
                return false;
            }

            if (!roleId.isEmpty()) {
                Role role = guild.getRoleById(roleId);
                if (role == null) {
                    getLogger().severe("Verified role tidak ditemukan: " + roleId);
                    return false;
                }
                if (!guild.getSelfMember().canInteract(role)) {
                    getLogger().warning("Role bot Discord harus berada di atas Verified role agar role dapat diberikan.");
                }
            }

            discordEnvironmentReady = true;
            getLogger().info("Discord environment VALID: guild/channel ditemukan.");
            return true;
        } catch (Throwable throwable) {
            getLogger().severe("Discord environment validation gagal: " + throwable.getMessage());
            return false;
        }
    }

    public boolean isDiscordEnvironmentReady() {
        return discordEnvironmentReady;
    }

    @Override
    public Set<PluginSlashCommand> getSlashCommands() {
        if (!getConfig().getBoolean("verification.discord.slash-command.enabled", true)) {
            return Collections.emptySet();
        }

        CommandData command = new CommandData("verify", "Verifikasi akun Minecraft Vephilim")
                .addOption(OptionType.STRING, "code", "Kode VPH-XXXXXX dari Minecraft", true);

        String guildId = getConfig().getString("verification.discord.guild-id", "").trim();
        PluginSlashCommand slash = guildId.isEmpty()
                ? new PluginSlashCommand(this, command)
                : new PluginSlashCommand(this, command, guildId);
        return Set.of(slash);
    }

    @SlashCommand(path = "verify", deferReply = true, deferEphemeral = true)
    public void onDiscordVerifyCommand(SlashCommandEvent event) {
        if (!getConfig().getBoolean("verification.discord.slash-command.enabled", true)) {
            event.getHook().sendMessage(message("discord.slash-disabled")).queue();
            return;
        }

        String configuredGuild = getConfig().getString("verification.discord.guild-id", "").trim();
        if (event.getGuild() == null || (!configuredGuild.isEmpty() && !configuredGuild.equals(event.getGuild().getId()))) {
            event.getHook().sendMessage(message("discord.wrong-guild")).queue();
            return;
        }

        var option = event.getOption("code");
        String code = option == null ? "" : option.getAsString();
        VerificationService.DiscordResult result = verificationService.verifyFromDiscord(code, event.getUser().getId());
        String reply = discordResultMessage(result);
        event.getHook().sendMessage(reply).queue();

        if (result.type() == VerificationService.ResultType.SUCCESS
                || result.type() == VerificationService.ResultType.REVERIFY_SUCCESS) {
            grantVerifiedRole(event.getGuild(), event.getMember());
            sendSuccessDm(event.getUser(), reply);
        }
    }

    public void publishVerificationPanel(CommandSender sender) {
        try {
            var jda = DiscordSRV.getPlugin().getJda();
            if (jda == null) {
                sender.sendMessage(legacyComponent("&cDiscordSRV/JDA belum siap."));
                return;
            }

            String guildId = getConfig().getString("verification.discord.guild-id", "").trim();
            String channelId = getConfig().getString("verification.discord.verification-channel-id", "").trim();
            Guild guild = jda.getGuildById(guildId);
            TextChannel channel = guild == null ? null : guild.getTextChannelById(channelId);
            if (channel == null) {
                sender.sendMessage(legacyComponent("&cVerification channel tidak ditemukan. Cek config.yml."));
                return;
            }

            String buttonLabel = getConfig().getString("verification.discord.panel.button-label", "Verifikasi Akun");
            channel.sendMessage(message("discord.panel"))
                    .setActionRow(Button.primary(DiscordInteractionListener.VERIFY_BUTTON_ID, buttonLabel))
                    .queue(
                            message -> sender.sendMessage(legacyComponent("&aPanel verifikasi berhasil dikirim. Message ID: &f" + message.getId())),
                            failure -> sender.sendMessage(legacyComponent("&cGagal mengirim panel: &f" + failure.getMessage()))
                    );
        } catch (Throwable throwable) {
            sender.sendMessage(legacyComponent("&cGagal mengirim panel: &f" + throwable.getMessage()));
        }
    }

    public String discordResultMessage(VerificationService.DiscordResult result) {
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
        placeholders.put("minutes", Long.toString(Math.max(1L, (result.remainingLockMillis() + 59_999L) / 60_000L)));
        return message(path, placeholders);
    }

    public void grantVerifiedRole(Guild guild, Member member) {
        String roleId = getConfig().getString("verification.discord.verified-role-id", "").trim();
        if (roleId.isEmpty() || guild == null || member == null) {
            return;
        }

        Role role = guild.getRoleById(roleId);
        if (role == null) {
            getLogger().warning("Verified role Discord tidak ditemukan: " + roleId);
            return;
        }

        guild.addRoleToMember(member, role).queue(
                ignored -> { },
                failure -> getLogger().warning("Gagal memberi verified role Discord: " + failure.getMessage())
        );
    }

    public void sendSuccessDm(github.scarsz.discordsrv.dependencies.jda.api.entities.User user, String reply) {
        if (!getConfig().getBoolean("verification.discord.dm-success", true) || user == null) {
            return;
        }
        user.openPrivateChannel().queue(channel ->
                channel.sendMessage(reply).queue(ignored -> { }, failure -> { })
        , failure -> { });
    }

    public String message(String path) {
        return message(path, Collections.emptyMap());
    }

    public String message(String path, Map<String, String> placeholders) {
        String value = messages.getString(path, "<missing message: " + path + ">");
        return replace(value, placeholders);
    }

    public Component component(String path, Map<String, String> placeholders) {
        return AMPERSAND.deserialize(message(path, placeholders));
    }

    public Component component(String path) {
        return component(path, Collections.emptyMap());
    }

    public Component legacyComponent(String value) {
        return AMPERSAND.deserialize(value == null ? "" : value);
    }

    public String replace(String value, Map<String, String> placeholders) {
        String output = value == null ? "" : value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            output = output.replace("%" + entry.getKey() + "%", entry.getValue() == null ? "" : entry.getValue());
        }
        return output;
    }

    public VerificationService getVerificationService() {
        return verificationService;
    }
}
