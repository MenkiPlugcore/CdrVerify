# CdrVerify

Discord-first account verification untuk **Vephilim Roleplay**.

CdrVerify menahan player Minecraft Java yang belum terverifikasi pada tahap pre-login, membuat kode sementara seperti `VPH-482731`, lalu menyelesaikan verifikasi dari Discord. Setelah Discord ID berhasil dihubungkan ke UUID Minecraft melalui DiscordSRV, player cukup join kembali.

## Target

- Paper 1.21.11
- Java 21
- DiscordSRV 1.30.5

## Flow v0.2.0

```text
Minecraft first join
        |
        v
UUID belum linked
        |
        v
Generate VPH-XXXXXX
        |
        v
Player ditolak + diberi kode
        |
        v
Buka #verifikasi Discord
        |
        v
Klik [ Verifikasi Akun ]
        |
        v
/verify code:VPH-XXXXXX
        |
        v
Ephemeral validation result
        |
        v
Discord ID <-> Minecraft UUID
        |
        v
Player join kembali
        |
        v
VERIFIED / ALLOWED
```

## Kenapa bukan Discord Modal?

DiscordSRV 1.30.5 menggunakan JDA 4.4.1. JDA tersebut mendukung Discord buttons tetapi belum menyediakan Modal API. CdrVerify sengaja tidak menambahkan JDA/bot kedua karena dapat menimbulkan classloader dan gateway-session conflict. UX yang dipakai adalah persistent button + native Discord slash command dengan ephemeral response.

## Setup

1. Install DiscordSRV 1.30.5 dan pastikan bot online.
2. Install hasil build CdrVerify ke `plugins/`.
3. Start server sekali.
4. Isi `plugins/CdrVerify/config.yml`:

```yaml
verification:
  discord:
    guild-id: "ID_SERVER_DISCORD"
    verification-channel-id: "ID_CHANNEL_VERIFIKASI"
    invite: "discord.gg/vephilim"
    verified-role-id: "ID_ROLE_VERIFIED"

security:
  ip-binding:
    mode: "SESSION"
    hash-salt: "GANTI_DENGAN_RANDOM_SECRET_PANJANG"
```

5. Jalankan `/cdrverify reload`.
6. Jalankan `/cdrverify doctor` dan pastikan Discord environment `VALID`.
7. Jalankan `/cdrverify panel` sekali untuk mengirim panel permanen ke channel verifikasi.

Panel tetap dapat digunakan setelah restart karena button custom ID bersifat stateless dan listener didaftarkan kembali ketika DiscordSRV/JDA siap.

## Discord UX

Player mendapat kode dari kick screen Minecraft, misalnya:

```text
VPH-482731
```

Di Discord player menggunakan:

```text
/verify code:VPH-482731
```

Hasil command bersifat ephemeral. Jika verifikasi sukses, link disimpan oleh AccountLinkManager DiscordSRV dan role Verified dapat diberikan otomatis.

Sebagai compatibility fallback, kode juga masih dapat ditempel langsung di channel verifikasi jika:

```yaml
verification:
  discord:
    allow-message-code: true
```

## Security

- UUID Minecraft adalah identitas utama Minecraft.
- Discord User ID adalah identitas Discord.
- 1 Discord tidak dapat mengambil alih UUID yang sudah terhubung ke Discord lain.
- Pending code memiliki TTL configurable.
- Brute-force lock tersedia.
- IP mode: `OFF`, `SESSION`, `STRICT`.
- Default yang direkomendasikan: `SESSION`.
- IP disimpan sebagai SHA-256 fingerprint dengan salt, bukan raw IP di metadata verifikasi.
- Button Discord memiliki cooldown.
- Pending dan verified metadata di-checkpoint berkala serta di-flush saat shutdown.

## Commands

```text
/cdrverify status <player|uuid>
/cdrverify code <player|uuid>
/cdrverify reset <player|uuid>
/cdrverify unlink <player|uuid>
/cdrverify force <player|uuid> <discord-id>
/cdrverify panel
/cdrverify doctor
/cdrverify reload
```

Permission:

```text
cdrverify.admin
```

## Discord bot permissions

Untuk verification channel:

- View Channel
- Send Messages
- Read Message History
- Manage Messages, jika `delete-submitted-code: true`
- Manage Roles, jika `verified-role-id` digunakan

Role DiscordSRV bot harus berada di atas role Verified.

## DiscordSRV built-in linking

CdrVerify adalah verification gate utama. Jangan aktifkan Require Link bawaan DiscordSRV bersamaan dengan CdrVerify karena akan menghasilkan dua lifecycle kode/kick yang berbeda. CdrVerify tetap memakai AccountLinkManager DiscordSRV sebagai sumber link akun resmi.

## Build

```bash
mvn clean verify
```

Output:

```text
target/CdrVerify-0.2.0-SNAPSHOT.jar
```

## Status

`0.2.0-SNAPSHOT` fokus pada Discord UX, slash verification, persistent button panel, diagnostics, dan runtime hardening. Setelah lolos test langsung di Vephilim, branch ini dapat dipromosikan menjadi kandidat `1.0.0`.
