package id.cadera.cdrverify.verification;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.objects.managers.AccountLinkManager;
import id.cadera.cdrverify.CdrVerifyPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VerificationService {

    public enum Purpose {
        INITIAL_LINK,
        IP_REVERIFY
    }

    public enum ResultType {
        SUCCESS,
        REVERIFY_SUCCESS,
        INVALID_CODE,
        LOCKED,
        DISCORD_ALREADY_LINKED,
        MINECRAFT_ALREADY_LINKED,
        WRONG_DISCORD,
        INTERNAL_ERROR
    }

    public record Session(
            UUID uuid,
            String username,
            String code,
            String ipHash,
            long createdAt,
            long expiresAt,
            Purpose purpose
    ) {
        public boolean expired(long now) {
            return expiresAt <= now;
        }

        public long remainingMillis(long now) {
            return Math.max(0L, expiresAt - now);
        }
    }

    public record JoinEvaluation(boolean allowed, boolean unavailable, Session session) {
        public static JoinEvaluation allow() {
            return new JoinEvaluation(true, false, null);
        }

        public static JoinEvaluation deny(Session session) {
            return new JoinEvaluation(false, false, session);
        }

        public static JoinEvaluation unavailableResult() {
            return new JoinEvaluation(false, true, null);
        }
    }

    public record DiscordResult(
            ResultType type,
            Session session,
            String playerName,
            long remainingLockMillis
    ) {
    }

    public record StatusSnapshot(
            UUID uuid,
            String username,
            String discordId,
            Session pending,
            String lastIpHash
    ) {
    }

    private record VerifiedMeta(String discordId, String ipHash, long verifiedAt, long lastSeenAt) {
    }

    private record AttemptState(int failures, long lockedUntil) {
    }

    private enum IpMode {
        OFF,
        SESSION,
        STRICT
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final CdrVerifyPlugin plugin;
    private final Object storageLock = new Object();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, UUID> codeIndex = new ConcurrentHashMap<>();
    private final Map<UUID, VerifiedMeta> verifiedMeta = new ConcurrentHashMap<>();
    private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();

    private File pendingFile;
    private File verifiedMetaFile;
    private File auditFile;

    public VerificationService(CdrVerifyPlugin plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Tidak dapat membuat folder data CdrVerify.");
        }

        pendingFile = new File(folder, plugin.getConfig().getString("storage.pending-file", "pending.yml"));
        verifiedMetaFile = new File(folder, plugin.getConfig().getString("storage.verified-meta-file", "verified-meta.yml"));
        auditFile = new File(folder, plugin.getConfig().getString("storage.audit-file", "audit.log"));
    }

    public void load() {
        synchronized (storageLock) {
            sessions.clear();
            codeIndex.clear();
            verifiedMeta.clear();

            loadPending();
            loadVerifiedMeta();
            cleanupExpiredLocked(System.currentTimeMillis());
            savePendingLocked();
        }
    }

    public void saveAll() {
        synchronized (storageLock) {
            savePendingLocked();
            saveVerifiedMetaLocked();
        }
    }

    public JoinEvaluation evaluateJoin(UUID uuid, String username, InetAddress address) {
        if (!plugin.getConfig().getBoolean("verification.enabled", true)) {
            return JoinEvaluation.allow();
        }

        if (isBypassed(uuid, username)) {
            return JoinEvaluation.allow();
        }

        AccountLinkManager manager = accountManager();
        if (manager == null) {
            return JoinEvaluation.unavailableResult();
        }

        String ip = address == null ? "unknown" : address.getHostAddress();
        String ipHash = ipMode() == IpMode.OFF ? "" : hashIp(ip);
        String discordId;

        try {
            discordId = manager.getDiscordId(uuid);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Gagal memeriksa link Discord untuk " + username + ": " + throwable.getMessage());
            return JoinEvaluation.unavailableResult();
        }

        if (discordId != null) {
            return evaluateAlreadyLinked(uuid, username, discordId, ipHash, ip);
        }

        Session session = getOrCreateSession(uuid, username, ipHash, Purpose.INITIAL_LINK, ip);
        return JoinEvaluation.deny(session);
    }

    private JoinEvaluation evaluateAlreadyLinked(
            UUID uuid,
            String username,
            String discordId,
            String ipHash,
            String maskedIpSource
    ) {
        IpMode mode = ipMode();

        if (mode != IpMode.STRICT) {
            Session removed = removeSession(uuid);
            if (removed != null) {
                audit("SESSION_CLEAR linked player=" + username + " uuid=" + uuid);
            }

            VerifiedMeta current = verifiedMeta.get(uuid);
            if (current == null || !safeEquals(current.discordId(), discordId) || !safeEquals(current.ipHash(), ipHash)) {
                long now = System.currentTimeMillis();
                verifiedMeta.put(uuid, new VerifiedMeta(discordId, ipHash, current == null ? now : current.verifiedAt(), now));
                saveVerifiedMeta();
            }
            return JoinEvaluation.allow();
        }

        VerifiedMeta current = verifiedMeta.get(uuid);
        long now = System.currentTimeMillis();

        if (current == null || !safeEquals(current.discordId(), discordId) || current.ipHash() == null || current.ipHash().isBlank()) {
            verifiedMeta.put(uuid, new VerifiedMeta(discordId, ipHash, now, now));
            saveVerifiedMeta();
            return JoinEvaluation.allow();
        }

        if (safeEquals(current.ipHash(), ipHash)) {
            if (sessions.containsKey(uuid)) {
                removeSession(uuid);
            }
            return JoinEvaluation.allow();
        }

        Session session = getOrCreateSession(uuid, username, ipHash, Purpose.IP_REVERIFY, maskedIpSource);
        return JoinEvaluation.deny(session);
    }

    public String extractCodeCandidate(String rawMessage) {
        if (rawMessage == null) {
            return null;
        }

        String value = rawMessage.trim();
        if (value.regionMatches(true, 0, "/verify ", 0, 8)) {
            value = value.substring(8).trim();
        } else if (value.regionMatches(true, 0, "verify ", 0, 7)) {
            value = value.substring(7).trim();
        }

        String prefix = codePrefix();
        if (prefix.isEmpty()) {
            if (!value.matches("\\d{" + codeDigits() + "}")) {
                return null;
            }
            return normalizeCode(value);
        }

        if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }

        return normalizeCode(value);
    }

    public DiscordResult verifyFromDiscord(String submittedCode, String discordId) {
        long now = System.currentTimeMillis();
        long lockedRemaining = lockRemaining(discordId, now);
        if (lockedRemaining > 0L) {
            return new DiscordResult(ResultType.LOCKED, null, null, lockedRemaining);
        }

        String normalized = normalizeCode(submittedCode);
        Session session;

        synchronized (storageLock) {
            cleanupExpiredLocked(now);
            UUID uuid = codeIndex.get(normalized);
            session = uuid == null ? null : sessions.get(uuid);
        }

        if (session == null || session.expired(now) || !session.code().equalsIgnoreCase(normalized)) {
            long newLock = recordFailure(discordId, now);
            return new DiscordResult(
                    newLock > 0L ? ResultType.LOCKED : ResultType.INVALID_CODE,
                    null,
                    null,
                    newLock
            );
        }

        AccountLinkManager manager = accountManager();
        if (manager == null) {
            return new DiscordResult(ResultType.INTERNAL_ERROR, session, session.username(), 0L);
        }

        try {
            UUID linkedUuid = manager.getUuid(discordId);
            String linkedDiscord = manager.getDiscordId(session.uuid());

            if (session.purpose() == Purpose.IP_REVERIFY) {
                if (linkedDiscord == null || !linkedDiscord.equals(discordId)) {
                    return new DiscordResult(ResultType.WRONG_DISCORD, session, session.username(), 0L);
                }
                if (linkedUuid != null && !linkedUuid.equals(session.uuid())) {
                    return new DiscordResult(ResultType.DISCORD_ALREADY_LINKED, session, session.username(), 0L);
                }

                completeSession(session, discordId, true);
                clearAttempts(discordId);
                audit("IP_REVERIFY_SUCCESS player=" + session.username() + " uuid=" + session.uuid() + " discord=" + discordId);
                return new DiscordResult(ResultType.REVERIFY_SUCCESS, session, session.username(), 0L);
            }

            if (linkedUuid != null && !linkedUuid.equals(session.uuid())) {
                return new DiscordResult(ResultType.DISCORD_ALREADY_LINKED, session, session.username(), 0L);
            }
            if (linkedDiscord != null && !linkedDiscord.equals(discordId)) {
                return new DiscordResult(ResultType.MINECRAFT_ALREADY_LINKED, session, session.username(), 0L);
            }

            if (linkedDiscord == null || linkedUuid == null) {
                manager.link(discordId, session.uuid());
            }

            completeSession(session, discordId, false);
            clearAttempts(discordId);
            audit("VERIFY_SUCCESS player=" + session.username() + " uuid=" + session.uuid() + " discord=" + discordId);
            return new DiscordResult(ResultType.SUCCESS, session, session.username(), 0L);
        } catch (Throwable throwable) {
            plugin.getLogger().severe("Verification failed for " + session.username() + ": " + throwable.getMessage());
            audit("VERIFY_ERROR player=" + session.username() + " uuid=" + session.uuid() + " error=" + throwable.getClass().getSimpleName());
            return new DiscordResult(ResultType.INTERNAL_ERROR, session, session.username(), 0L);
        }
    }

    public Optional<Session> getSession(UUID uuid) {
        Session session = sessions.get(uuid);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expired(System.currentTimeMillis())) {
            removeSession(uuid);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public StatusSnapshot status(UUID uuid, String username) {
        AccountLinkManager manager = accountManager();
        String discordId = null;
        if (manager != null) {
            try {
                discordId = manager.getDiscordId(uuid);
            } catch (Throwable ignored) {
            }
        }
        VerifiedMeta meta = verifiedMeta.get(uuid);
        return new StatusSnapshot(uuid, username, discordId, getSession(uuid).orElse(null), meta == null ? null : meta.ipHash());
    }

    public void resetSession(UUID uuid) {
        Session removed = removeSession(uuid);
        if (removed != null) {
            audit("SESSION_RESET player=" + removed.username() + " uuid=" + uuid);
        }
    }

    public void unlink(UUID uuid) {
        AccountLinkManager manager = accountManager();
        if (manager != null) {
            manager.unlink(uuid);
        }
        resetSession(uuid);
        verifiedMeta.remove(uuid);
        saveVerifiedMeta();
        audit("ACCOUNT_UNLINK uuid=" + uuid);
    }

    public boolean forceLink(UUID uuid, String username, String discordId) {
        AccountLinkManager manager = accountManager();
        if (manager == null) {
            return false;
        }

        UUID existingUuid = manager.getUuid(discordId);
        String existingDiscord = manager.getDiscordId(uuid);
        if ((existingUuid != null && !existingUuid.equals(uuid)) || (existingDiscord != null && !existingDiscord.equals(discordId))) {
            return false;
        }

        if (existingDiscord == null || existingUuid == null) {
            manager.link(discordId, uuid);
        }

        long now = System.currentTimeMillis();
        verifiedMeta.put(uuid, new VerifiedMeta(discordId, "", now, now));
        saveVerifiedMeta();
        resetSession(uuid);
        audit("FORCE_LINK player=" + username + " uuid=" + uuid + " discord=" + discordId);
        return true;
    }

    public boolean isVerificationChannel(String guildId, String channelId) {
        String configuredGuild = plugin.getConfig().getString("verification.discord.guild-id", "").trim();
        String configuredChannel = plugin.getConfig().getString("verification.discord.verification-channel-id", "").trim();

        if (configuredChannel.isEmpty() || !configuredChannel.equals(channelId)) {
            return false;
        }
        return configuredGuild.isEmpty() || configuredGuild.equals(guildId);
    }

    public long remainingMinutes(Session session) {
        if (session == null) {
            return 0L;
        }
        long millis = session.remainingMillis(System.currentTimeMillis());
        return Math.max(1L, (millis + 59_999L) / 60_000L);
    }

    private Session getOrCreateSession(UUID uuid, String username, String ipHash, Purpose purpose, String rawIp) {
        long now = System.currentTimeMillis();
        Session current = sessions.get(uuid);
        boolean reuse = plugin.getConfig().getBoolean("verification.code.reuse-until-expired", true);
        boolean regenerateOnIpChange = plugin.getConfig().getBoolean("verification.code.regenerate-on-ip-change", true);

        boolean samePurpose = current != null && current.purpose() == purpose;
        boolean sameIp = current != null && safeEquals(current.ipHash(), ipHash);
        boolean canReuse = current != null
                && !current.expired(now)
                && reuse
                && samePurpose
                && (sameIp || !regenerateOnIpChange || ipMode() == IpMode.OFF);

        if (canReuse) {
            return current;
        }

        String reason;
        if (current == null) {
            reason = "NEW";
        } else if (current.expired(now)) {
            reason = "EXPIRED";
        } else if (!samePurpose) {
            reason = "PURPOSE_CHANGE";
        } else if (!sameIp) {
            reason = "IP_CHANGE";
        } else {
            reason = "ROTATE";
        }

        Session next;
        synchronized (storageLock) {
            Session old = sessions.remove(uuid);
            if (old != null) {
                codeIndex.remove(normalizeCode(old.code()));
            }

            String code = generateUniqueCodeLocked();
            int expireMinutes = Math.max(1, plugin.getConfig().getInt("verification.code.expire-minutes", 15));
            long expiresAt = now + expireMinutes * 60_000L;
            next = new Session(uuid, username, code, ipHash, now, expiresAt, purpose);
            sessions.put(uuid, next);
            codeIndex.put(normalizeCode(code), uuid);
            savePendingLocked();
        }

        audit("CODE_GENERATED reason=" + reason
                + " purpose=" + purpose
                + " player=" + username
                + " uuid=" + uuid
                + " ip=" + maskIp(rawIp));
        return next;
    }

    private void completeSession(Session session, String discordId, boolean reverify) {
        long now = System.currentTimeMillis();
        synchronized (storageLock) {
            Session current = sessions.get(session.uuid());
            if (current != null && current.code().equalsIgnoreCase(session.code())) {
                sessions.remove(session.uuid());
                codeIndex.remove(normalizeCode(session.code()));
            }

            VerifiedMeta existing = verifiedMeta.get(session.uuid());
            long verifiedAt = reverify && existing != null ? existing.verifiedAt() : now;
            verifiedMeta.put(session.uuid(), new VerifiedMeta(discordId, session.ipHash(), verifiedAt, now));
            savePendingLocked();
            saveVerifiedMetaLocked();
        }
    }

    private Session removeSession(UUID uuid) {
        synchronized (storageLock) {
            Session removed = sessions.remove(uuid);
            if (removed != null) {
                codeIndex.remove(normalizeCode(removed.code()));
                savePendingLocked();
            }
            return removed;
        }
    }

    private void loadPending() {
        if (!pendingFile.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(pendingFile);
        ConfigurationSection root = yaml.getConfigurationSection("sessions");
        if (root == null) {
            return;
        }

        long now = System.currentTimeMillis();
        for (String key : root.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String base = "sessions." + key + ".";
                String username = yaml.getString(base + "username", "unknown");
                String code = yaml.getString(base + "code", "");
                String ipHash = yaml.getString(base + "ip-hash", "");
                long createdAt = yaml.getLong(base + "created-at", now);
                long expiresAt = yaml.getLong(base + "expires-at", 0L);
                Purpose purpose = Purpose.valueOf(yaml.getString(base + "purpose", Purpose.INITIAL_LINK.name()));

                if (code.isBlank() || expiresAt <= now) {
                    continue;
                }

                Session session = new Session(uuid, username, normalizeCode(code), ipHash, createdAt, expiresAt, purpose);
                sessions.put(uuid, session);
                codeIndex.put(normalizeCode(code), uuid);
            } catch (Exception exception) {
                plugin.getLogger().warning("Mengabaikan pending session rusak: " + key);
            }
        }
    }

    private void loadVerifiedMeta() {
        if (!verifiedMetaFile.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(verifiedMetaFile);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            return;
        }

        for (String key : root.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String base = "players." + key + ".";
                verifiedMeta.put(uuid, new VerifiedMeta(
                        yaml.getString(base + "discord-id", ""),
                        yaml.getString(base + "ip-hash", ""),
                        yaml.getLong(base + "verified-at", 0L),
                        yaml.getLong(base + "last-seen-at", 0L)
                ));
            } catch (Exception exception) {
                plugin.getLogger().warning("Mengabaikan verified metadata rusak: " + key);
            }
        }
    }

    private void savePending() {
        synchronized (storageLock) {
            savePendingLocked();
        }
    }

    private void savePendingLocked() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Session session : sessions.values()) {
            String base = "sessions." + session.uuid() + ".";
            yaml.set(base + "username", session.username());
            yaml.set(base + "code", session.code());
            yaml.set(base + "ip-hash", session.ipHash());
            yaml.set(base + "created-at", session.createdAt());
            yaml.set(base + "expires-at", session.expiresAt());
            yaml.set(base + "purpose", session.purpose().name());
        }
        saveYaml(yaml, pendingFile);
    }

    private void saveVerifiedMeta() {
        synchronized (storageLock) {
            saveVerifiedMetaLocked();
        }
    }

    private void saveVerifiedMetaLocked() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, VerifiedMeta> entry : verifiedMeta.entrySet()) {
            String base = "players." + entry.getKey() + ".";
            VerifiedMeta meta = entry.getValue();
            yaml.set(base + "discord-id", meta.discordId());
            yaml.set(base + "ip-hash", meta.ipHash());
            yaml.set(base + "verified-at", meta.verifiedAt());
            yaml.set(base + "last-seen-at", meta.lastSeenAt());
        }
        saveYaml(yaml, verifiedMetaFile);
    }

    private void saveYaml(YamlConfiguration yaml, File file) {
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Gagal menyimpan " + file.getName() + ": " + exception.getMessage());
        }
    }

    private void cleanupExpiredLocked(long now) {
        List<UUID> expired = new ArrayList<>();
        for (Session session : sessions.values()) {
            if (session.expired(now)) {
                expired.add(session.uuid());
            }
        }
        for (UUID uuid : expired) {
            Session removed = sessions.remove(uuid);
            if (removed != null) {
                codeIndex.remove(normalizeCode(removed.code()));
            }
        }
    }

    private String generateUniqueCodeLocked() {
        int digits = codeDigits();
        String prefix = codePrefix();
        String code;
        do {
            StringBuilder builder = new StringBuilder(prefix);
            for (int i = 0; i < digits; i++) {
                builder.append(SECURE_RANDOM.nextInt(10));
            }
            code = normalizeCode(builder.toString());
        } while (codeIndex.containsKey(code));
        return code;
    }

    private int codeDigits() {
        return Math.max(4, Math.min(10, plugin.getConfig().getInt("verification.code.digits", 4)));
    }

    private String codePrefix() {
        String prefix = plugin.getConfig().getString("verification.code.prefix", "");
        return prefix == null ? "" : prefix.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private IpMode ipMode() {
        String raw = plugin.getConfig().getString("security.ip-binding.mode", "SESSION");
        try {
            return IpMode.valueOf(raw == null ? "SESSION" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return IpMode.SESSION;
        }
    }

    private String hashIp(String ip) {
        String salt = plugin.getConfig().getString("security.ip-binding.hash-salt", "CdrVerify");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((salt + ":" + ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private boolean isBypassed(UUID uuid, String username) {
        for (String configuredUuid : plugin.getConfig().getStringList("bypass.uuids")) {
            if (uuid.toString().equalsIgnoreCase(configuredUuid.trim())) {
                return true;
            }
        }
        for (String configuredName : plugin.getConfig().getStringList("bypass.usernames")) {
            if (username.equalsIgnoreCase(configuredName.trim())) {
                return true;
            }
        }
        return false;
    }

    private long lockRemaining(String discordId, long now) {
        if (!plugin.getConfig().getBoolean("security.brute-force.enabled", true)) {
            return 0L;
        }
        AttemptState state = attempts.get(discordId);
        if (state == null || state.lockedUntil() <= now) {
            if (state != null && state.lockedUntil() > 0L) {
                attempts.remove(discordId, state);
            }
            return 0L;
        }
        return state.lockedUntil() - now;
    }

    private long recordFailure(String discordId, long now) {
        if (!plugin.getConfig().getBoolean("security.brute-force.enabled", true)) {
            return 0L;
        }

        int max = Math.max(1, plugin.getConfig().getInt("security.brute-force.max-failed-attempts", 5));
        int lockMinutes = Math.max(1, plugin.getConfig().getInt("security.brute-force.lock-minutes", 5));

        AttemptState state = attempts.compute(discordId, (key, existing) -> {
            if (existing != null && existing.lockedUntil() > now) {
                return existing;
            }
            int failures = existing == null ? 1 : existing.failures() + 1;
            if (failures >= max) {
                return new AttemptState(0, now + lockMinutes * 60_000L);
            }
            return new AttemptState(failures, 0L);
        });

        return state != null && state.lockedUntil() > now ? state.lockedUntil() - now : 0L;
    }

    private void clearAttempts(String discordId) {
        attempts.remove(discordId);
    }

    private AccountLinkManager accountManager() {
        try {
            return DiscordSRV.getPlugin().getAccountLinkManager();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void audit(String line) {
        String record = Instant.now() + " " + line + System.lineSeparator();
        synchronized (storageLock) {
            try {
                Files.writeString(
                        auditFile.toPath(),
                        record,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException exception) {
                plugin.getLogger().warning("Gagal menulis audit.log: " + exception.getMessage());
            }
        }
    }

    private String maskIp(String ip) {
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            return "unknown";
        }
        if (ip.contains(".")) {
            String[] parts = ip.split("\\.");
            if (parts.length == 4) {
                return parts[0] + "." + parts[1] + "." + parts[2] + ".xxx";
            }
        }
        if (ip.contains(":")) {
            String[] parts = ip.split(":");
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(3, parts.length); i++) {
                if (i > 0) out.append(':');
                out.append(parts[i]);
            }
            return out.append(":xxxx:xxxx").toString();
        }
        return "masked";
    }

    private boolean safeEquals(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }
}
