# Changelog

## 0.5.0-SNAPSHOT

Trusted-login security update.

### Added

- Strict trusted-IP protection for already verified Minecraft accounts.
- Up to 3 trusted IP fingerprints per account by default.
- Trusted IP expiry after 30 days of inactivity by default.
- Unknown IP login challenge using the existing 4-digit verification code.
- New-IP challenge can only be approved by the Discord account already linked to that Minecraft UUID.
- Automatic LRU-style replacement when the trusted-IP slot limit is reached.
- Seamless migration from v0.4.x single-IP metadata into the trusted-IP list.
- `/cdrverify trusted <player|uuid>` to inspect trusted login fingerprints.
- `/cdrverify revoke-trusted <player|uuid>` to revoke all trusted IPs and force Discord confirmation on the next STRICT login.
- Trusted-IP details in `/cdrverify status` and `/cdrverify doctor`.

### Changed

- Default IP mode is now `STRICT` for new installations.
- Direct 4-digit Discord message verification remains the primary verification method.
- Security-check copy now clearly explains that only the already-linked Discord account can approve an unknown IP.
- Verified login metadata now stores multiple hashed trusted IP fingerprints instead of relying on only one last IP.

### Security

- Raw IP addresses are not stored in verified metadata; only salted SHA-256 fingerprints are persisted.
- A stolen Minecraft account logging in from an unknown IP is blocked until the linked Discord account confirms the challenge.
- 4-digit codes remain one-time, expire after the configured TTL, and are protected by failed-attempt locking.

## 0.4.2-SNAPSHOT

Direct-code Discord verification update.

### Changed

- Players type the 4-digit code directly into the verification channel instead of using `/verify`.
- Submitted verification codes are deleted automatically from the Discord channel.
- Slash `/verify` is disabled by default.
- `/cdrverify doctor` reports direct-code mode as the primary flow.

## 0.4.1-SNAPSHOT

Numeric-code hardening update.

### Changed

- Verification code changed to 4 numeric digits with no `VPH-` prefix.
- Code generation now uses `SecureRandom`.
- Failed attempts default to 3 before a temporary lock.

## 0.4.0-SNAPSHOT

Secure challenge-code verification update.

### Changed

- Restored the stronger one-time verification flow.
- Players must try to join Minecraft first to receive an active verification code.
- Verification success shows both Discord username and Minecraft nickname.
- Active code is bound to the pending Minecraft UUID session.
- Code is one-time and removed after successful verification.
- Code expires after a configurable TTL, default 15 minutes.
- Brute-force lock and one-to-one Discord/Minecraft conflict checks remain enabled.

## 0.3.0-SNAPSHOT

Experimental Discord-first nickname verification update.

### Added

- Pending nickname verification based on UUID + Minecraft nickname.
- Native Discord `/verify nick:<nickname>` command.
- Pending nickname lookup with configurable expiry window.

### Security note

Nickname verification was smoother but did not provide the same ownership proof as a one-time secret. It was replaced by challenge-code verification.

## 0.2.0-SNAPSHOT

Discord UX and production-hardening update.

### Added

- Native Discord verification command with ephemeral responses.
- Persistent verification panel with **Verifikasi Akun** button.
- `/cdrverify panel` and `/cdrverify doctor`.
- Guild, channel, role, and hierarchy validation.
- Periodic storage checkpoint.

## 0.1.0-SNAPSHOT

Initial CdrVerify development build.

### Added

- Discord-first Minecraft account verification flow.
- Persistent verification sessions.
- DiscordSRV official account-link integration.
- One-to-one Minecraft UUID and Discord ID conflict protection.
- Configurable IP modes: `OFF`, `SESSION`, `STRICT`.
- SHA-256 IP fingerprint storage with configurable salt.
- Brute-force lock for invalid Discord verification attempts.
- Optional Discord verified role and success DM.
- Admin status, code, reset, unlink, force-link, and reload commands.
- Audit logging.
- Java 21 / Paper 1.21.11 build workflow.
