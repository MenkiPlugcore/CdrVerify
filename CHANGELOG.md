# Changelog

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

- Verification instructions now prioritize the native Discord `/verify` command.
- Discord success/error formatting is shared between slash-command and legacy message flows.
- Version bumped to `0.2.0-SNAPSHOT`.

### Compatibility note

DiscordSRV 1.30.5 ships JDA 4.4.1, which supports buttons but does not provide Discord Modal APIs. For that reason CdrVerify uses a persistent button plus native slash-command input instead of introducing a second Discord bot/JDA runtime.

## 0.1.0-SNAPSHOT

Initial CdrVerify development build.

### Added

- Discord-first Minecraft account verification flow.
- Persistent `VPH-XXXXXX` verification sessions.
- Configurable 10–20+ minute TTL through `expire-minutes`.
- DiscordSRV official account-link integration.
- One-to-one Minecraft UUID and Discord ID conflict protection.
- Configurable IP modes: `OFF`, `SESSION`, `STRICT`.
- Pending-session code rotation on IP change.
- SHA-256 IP fingerprint storage with configurable salt.
- Brute-force lock for invalid Discord verification attempts.
- Optional Discord verified role and success DM.
- Admin status, code, reset, unlink, force-link, and reload commands.
- Audit logging.
- Java 21 / Paper 1.21.11 build workflow.
