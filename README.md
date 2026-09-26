# CdrVerify

Discord-first account verification untuk **Vephilim Roleplay**.

CdrVerify menahan player Minecraft Java yang belum terverifikasi pada tahap pre-login, mencatat UUID + nickname sebagai pending session, lalu menyelesaikan verifikasi dari Discord. Player tidak perlu lagi menyalin kode dari Minecraft.

## Target

- Paper 1.21.11
- Java 21
- DiscordSRV 1.30.5

## Flow v0.3.0

```text
Minecraft first join
        |
        v
UUID belum linked
        |
        v
CdrVerify catat pending UUID + nickname
        |
        v
Player ditolak + diarahkan ke Discord
        |
        v
Buka #verifikasi Discord
        |
        v
Klik [ Verifikasi Akun ]
        |
        v
/verify nick:Caderaaa
        |
        v
CdrVerify cari pending nickname aktif
        |
        v
Discord ID <-> Minecraft UUID
        |
        v
VERIFIED
        |
        v
Player join kembali
```

Tidak ada lagi token `VPH-XXXXXX` pada flow player.

## Setup

1. Install DiscordSRV 1.30.5 dan pastikan bot online.
2. Install hasil build CdrVerify ke `plugins/`.
3. Start server sekali.
4. Isi `plugins/CdrVerify/config.yml`:

```yaml
verification:
  pending:
    expire-minutes: 15

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
7. Jalankan `/cdrverify panel` sekali untuk mengirim panel permanen.

## Discord UX

Setelah player mencoba join Minecraft, player menjalankan:

```text
/verify nick:Caderaaa
```

Command hanya menerima nickname yang memiliki pending join aktif dan belum kedaluwarsa. Hasil command bersifat ephemeral.

Contoh sukses:

```text
✅ VERIFIKASI BERHASIL

Discord:
CandraFirmansyah

Nick Minecraft:
Caderaaa

Status:
VERIFIED

Silakan kembali ke Vephilim Roleplay.
```

## Security

- UUID Minecraft tetap menjadi identitas utama Minecraft.
- Discord User ID menjadi identitas Discord.
- 1 Discord tidak dapat mengambil alih UUID yang sudah terhubung ke Discord lain.
- Nickname hanya dapat diklaim saat ada pending join aktif.
- Pending default kedaluwarsa setelah 15 menit.
- Percobaan nickname salah/unknown terkena brute-force lock.
- IP mode tersedia: `OFF`, `SESSION`, `STRICT`.
- Default yang direkomendasikan: `SESSION`.
- IP disimpan sebagai SHA-256 fingerprint dengan salt.
- `STRICT` mengharuskan perubahan IP dikonfirmasi dari Discord yang sudah terhubung.
- Button Discord memiliki cooldown.
- Pending dan verified metadata di-checkpoint berkala dan di-flush saat shutdown.
- Plugin memperingatkan administrator jika server menggunakan `online-mode=false` karena nickname-only claim menjadi lebih lemah.

> Catatan: nickname-only verification mengoptimalkan UX, tetapi tidak sekuat challenge token atau autentikasi Microsoft. Gunakan `online-mode=true` untuk model ini jika memungkinkan.

## Commands

```text
/cdrverify status <player|uuid>
/cdrverify pending <player|uuid>
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
- Use Application Commands
- Manage Roles, jika `verified-role-id` digunakan

Role DiscordSRV bot harus berada di atas role Verified.

Member biasa tidak perlu permission `Send Messages` karena verification memakai slash command.

## DiscordSRV built-in linking

CdrVerify adalah verification gate utama. Jangan aktifkan Require Link bawaan DiscordSRV bersamaan dengan CdrVerify. CdrVerify tetap menggunakan AccountLinkManager DiscordSRV sebagai sumber link akun resmi.

## Upgrade dari v0.2.x

Pending session lama yang masih memiliki field `code` tetap dapat dibaca karena v0.3.0 mengabaikan field tersebut dan menggunakan UUID + username + expiry. Setelah save berikutnya, field code lama tidak ditulis lagi.

`messages.yml` memakai key baru `*-v3` untuk flow yang berubah, sehingga instalasi lama dapat menerima pesan baru tanpa menimpa custom message lama secara paksa.

## Build

```bash
mvn clean verify
```

Output:

```text
target/CdrVerify-0.3.0-SNAPSHOT.jar
```

## Status

`0.3.0-SNAPSHOT` adalah kandidat Discord-first nickname flow. Setelah lolos runtime test langsung di Vephilim, versi ini dapat dipromosikan menuju `1.0.0`.
