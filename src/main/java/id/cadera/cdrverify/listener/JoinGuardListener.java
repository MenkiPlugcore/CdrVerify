package id.cadera.cdrverify.listener;

import id.cadera.cdrverify.CdrVerifyPlugin;
import id.cadera.cdrverify.verification.VerificationService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Map;

public final class JoinGuardListener implements Listener {

    private final CdrVerifyPlugin plugin;
    private final VerificationService verificationService;

    public JoinGuardListener(CdrVerifyPlugin plugin, VerificationService verificationService) {
        this.plugin = plugin;
        this.verificationService = verificationService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        VerificationService.JoinEvaluation evaluation = verificationService.evaluateJoin(
                event.getUniqueId(),
                event.getName(),
                event.getAddress()
        );

        if (evaluation.allowed()) {
            return;
        }

        if (evaluation.unavailable()) {
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.component("kick.unavailable")
            );
            return;
        }

        VerificationService.Session session = evaluation.session();
        String messagePath = session.purpose() == VerificationService.Purpose.IP_REVERIFY
                ? "kick.ip-reverify"
                : "kick.unverified";

        event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                plugin.component(messagePath, Map.of(
                        "code", session.code(),
                        "minutes", Long.toString(verificationService.remainingMinutes(session)),
                        "invite", plugin.getConfig().getString("verification.discord.invite", "")
                ))
        );
    }
}
