# Changelog

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
