package id.cadera.cdrverify;

import github.scarsz.discordsrv.DiscordSRV;
import id.cadera.cdrverify.command.CdrVerifyCommand;
import id.cadera.cdrverify.listener.DiscordVerificationListener;
import id.cadera.cdrverify.listener.JoinGuardListener;
import id.cadera.cdrverify.verification.VerificationService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.Map;

public final class CdrVerifyPlugin extends JavaPlugin {

    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private VerificationService verificationService;
    private DiscordVerificationListener discordListener;
    private YamlConfiguration messages;

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

        discordListener = new DiscordVerificationListener(this, verificationService);
        DiscordSRV.api.subscribe(discordListener);

        CdrVerifyCommand command = new CdrVerifyCommand(this, verificationService);
        if (getCommand("cdrverify") != null) {
            getCommand("cdrverify").setExecutor(command);
            getCommand("cdrverify").setTabCompleter(command);
        }

        validateConfiguration();

        getLogger().info("CdrVerify v" + getDescription().getVersion() + " enabled.");
        getLogger().info("Verification flow: Minecraft join -> Discord code -> rejoin.");
    }

    @Override
    public void onDisable() {
        if (discordListener != null) {
            try {
                DiscordSRV.api.unsubscribe(discordListener);
            } catch (Throwable ignored) {
                // DiscordSRV may already be shutting down.
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
        validateConfiguration();
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

    private void validateConfiguration() {
        String channelId = getConfig().getString("verification.discord.verification-channel-id", "").trim();
        if (channelId.isEmpty()) {
            getLogger().warning("verification.discord.verification-channel-id belum diisi. Kode Discord belum dapat diproses.");
        }

        String salt = getConfig().getString("security.ip-binding.hash-salt", "");
        if (salt.isBlank() || "CHANGE-ME-CdrVerify".equals(salt)) {
            getLogger().warning("Ganti security.ip-binding.hash-salt sebelum production agar hash IP tidak memakai salt default.");
        }

        String mode = getConfig().getString("security.ip-binding.mode", "SESSION").toUpperCase();
        if (!mode.equals("OFF") && !mode.equals("SESSION") && !mode.equals("STRICT")) {
            getLogger().warning("security.ip-binding.mode tidak valid: " + mode + ". CdrVerify akan memperlakukannya sebagai SESSION.");
        }
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
