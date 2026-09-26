# Changelog

## 0.4.0-SNAPSHOT

Secure challenge-code verification update.

### Changed

- Restored the stronger `VPH-XXXXXX` one-time verification flow.
- Players must try to join Minecraft first to receive an active verification code.
- Discord verification uses native `/verify code:<kode>` with an ephemeral response.
- Verification success now shows both Discord username and Minecraft nickname.
- Verification channel can remain read-only for `@everyone`.
- Legacy pasted-code verification is disabled by default.
- Success DM is disabled by default because the slash-command response is already private.
- Discord panel and button copy were rewritten to make the login-first flow clearer.

### Security

- Active code is bound to the pending Minecraft UUID session.
- Code is one-time and removed after successful verification.
- Code expires after a configurable TTL, default 15 minutes.
- Active code is reused across repeated joins until expiry unless IP-session rules rotate it.
- Brute-force lock and one-to-one Discord/Minecraft conflict checks remain enabled.

## 0.3.0-SNAPSHOT

True Discord-first nickname verification update.

### Added

- Pending nickname verification based on UUID + Minecraft nickname.
- Native Discord `/verify nick:<nickname>` command.
- Pending nickname lookup with configurable expiry window.
- Rate-limit / brute-force lock for unknown or mistyped nickname claims.
- Discord success output showing both Discord username and Minecraft nickname.
- Offline-mode warning because nickname-only ownership proof is weaker when UUID identity is not authoritative.
- Automatic merge of new `messages.yml` defaults without deleting existing custom entries.

### Changed

- Player no longer receives or copies `VPH-XXXXXX` codes.
- First join now only creates a pending account claim and redirects the player to Discord.
- Verification panel and button instructions now reference Minecraft nickname instead of verification code.
- `/cdrverify code` replaced by `/cdrverify pending`.
- Pending storage no longer writes verification codes.
- `STRICT` IP re-verification now uses the linked Discord account + Minecraft nickname.
- Version bumped to `0.3.0-SNAPSHOT`.

### Removed

- Legacy pasted-code Discord message listener.
- Player-facing VPH verification token workflow.
- `allow-message-code`, code-generation, and code-rotation UX.

### Security note

Nickname verification is accepted only while the nickname has an active pending join session. This is intentionally smoother than a challenge-token flow, but it does not provide the same proof strength as a one-time secret or Microsoft authentication. `online-mode=true` is strongly recommended for this model.

## 0.2.0-SNAPSHOT

Discord UX and production-hardening update.

### Added

- Native Discord `/verify code:<kode>` command with ephemeral responses.
- Persistent verification panel with **Verifikasi Akun** button.
- `/cdrverify panel` to publish the verification panel.
- `/cdrverify doctor` to validate Discord guild/channel/role configuration.
- JDA listener lifecycle management with delayed registration until DiscordSRV is ready.
- Dynamic validation for guild, verification channel, verified role, and role hierarchy.
- Button interaction cooldown.
- Periodic storage checkpoint.
- Config switch for legacy pasted-code verification.
- Slash-command refresh on CdrVerify reload.

### Changed

- Verification instructions prioritize the native Discord `/verify` command.
- Version bumped to `0.2.0-SNAPSHOT`.

## 0.1.0-SNAPSHOT

Initial CdrVerify development build.

### Added

- Discord-first Minecraft account verification flow.
- Persistent `VPH-XXXXXX` verification sessions.
- DiscordSRV official account-link integration.
- One-to-one Minecraft UUID and Discord ID conflict protection.
- Configurable IP modes: `OFF`, `SESSION`, `STRICT`.
- SHA-256 IP fingerprint storage with configurable salt.
- Brute-force lock for invalid Discord verification attempts.
- Optional Discord verified role and success DM.
- Admin status, code, reset, unlink, force-link, and reload commands.
- Audit logging.
- Java 21 / Paper 1.21.11 build workflow.
