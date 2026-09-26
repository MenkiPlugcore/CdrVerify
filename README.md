# CdrVerify

Discord-first account verification untuk **Vephilim Roleplay**.

CdrVerify menahan player Minecraft yang belum terverifikasi pada tahap pre-login, membuat kode sementara seperti `VPH-482731`, lalu menyelesaikan verifikasi sepenuhnya dari Discord. Setelah akun Discord berhasil dihubungkan ke UUID Minecraft, player cukup join kembali dan langsung masuk server.

## Target

- Paper 1.21.11
- Java 21
- DiscordSRV 1.30.5

## Flow

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
Player ditolak dengan instruksi Discord
        |
        v
Kirim VPH-XXXXXX di #verifikasi
        |
        v
CdrVerify validasi sesi + Discord ID
        |
        v
DiscordSRV link: Discord ID <-> Minecraft UUID
        |
        v
Kode dihancurkan
        |
        v
Player join kembali
        |
        v
VERIFIED / ALLOWED
```

## Fitur v0.1.0

- First-join verification gate pada `AsyncPlayerPreLoginEvent`.
- Kode configurable dengan prefix `VPH-` dan default 6 digit.
- TTL default 15 menit dan dapat diubah dari `config.yml`.
- Kode aktif dipertahankan saat player disconnect/rejoin.
- Pending session disimpan ke disk sehingga tidak hilang hanya karena restart server.
- Verifikasi dilakukan langsung dari channel Discord khusus.
- Link yang sukses dibuat melalui `AccountLinkManager` DiscordSRV.
- Kebijakan 1 Discord = 1 Minecraft dan 1 Minecraft = 1 Discord sebelum link dilakukan.
- IP binding `OFF`, `SESSION`, atau `STRICT`.
- Default `SESSION`: perubahan IP selama sesi pending dapat merotasi kode, tetapi akun yang sudah verified tidak dipaksa verifikasi ulang.
- IP disimpan sebagai SHA-256 fingerprint dengan salt, bukan raw IP.
- Brute-force lock untuk percobaan kode salah.
- Optional Discord Verified role.
- Optional delete pesan kode dari channel verifikasi.
- Optional DM sukses.
- Audit log.
- Admin commands untuk inspeksi/reset/unlink/force link.

## Instalasi

1. Install **DiscordSRV** dan pastikan bot DiscordSRV sudah online.
2. Build/install `CdrVerify-0.1.0-SNAPSHOT.jar` ke folder `plugins/`.
3. Start server sekali agar `plugins/CdrVerify/config.yml` dibuat.
4. Isi minimal konfigurasi berikut:

```yaml
verification:
  discord:
    guild-id: "ID_SERVER_DISCORD"
    verification-channel-id: "ID_CHANNEL_VERIFIKASI"
    invite: "discord.gg/contoh"
    verified-role-id: "ID_ROLE_VERIFIED"

security:
  ip-binding:
    mode: "SESSION"
    hash-salt: "GANTI_DENGAN_RANDOM_SECRET_YANG_PANJANG"
```

5. Restart server atau jalankan `/cdrverify reload`.

### Mendapatkan Discord ID

Aktifkan **Developer Mode** di Discord, lalu gunakan **Copy Server ID**, **Copy Channel ID**, atau **Copy User ID**.

## Penting: DiscordSRV built-in linking

CdrVerify adalah gate verifikasi utama. Agar tidak terjadi double-kick atau dua sistem kode yang berjalan bersamaan:

- Jangan aktifkan modul **Require Link** DiscordSRV bersamaan dengan CdrVerify.
- Jangan petakan channel `#verifikasi` CdrVerify sebagai channel built-in `link` DiscordSRV.

CdrVerify tetap menggunakan database/link manager DiscordSRV sebagai sumber link akun resmi, tetapi lifecycle kode `VPH-XXXXXX` dikelola oleh CdrVerify.

## Permission bot Discord

Minimum untuk channel verifikasi:

- View Channel
- Send Messages
- Read Message History

Jika `delete-submitted-code: true`, berikan permission **Manage Messages** agar kode player dapat dibersihkan dari channel.

Jika `verified-role-id` dipakai, bot juga membutuhkan **Manage Roles** dan role bot harus berada di atas role Verified.

## Konfigurasi kode

```yaml
verification:
  code:
    prefix: "VPH-"
    digits: 6
    expire-minutes: 15
    reuse-until-expired: true
    regenerate-on-ip-change: true
```

Contoh TTL:

- 10 menit: `expire-minutes: 10`
- 15 menit: `expire-minutes: 15`
- 20 menit: `expire-minutes: 20`

## IP binding

```yaml
security:
  ip-binding:
    mode: "SESSION"
```

Mode:

- `OFF`: IP tidak ikut menentukan sesi verifikasi.
- `SESSION`: IP hanya melindungi sesi yang belum selesai. Ini default yang direkomendasikan.
- `STRICT`: perubahan IP akun yang sudah linked meminta konfirmasi ulang dari Discord yang sama.

`SESSION` tidak mencabut verifikasi player hanya karena ISP, hotspot, modem, atau jaringan player mengganti IP.

## Discord verification

Player cukup mengirim salah satu bentuk berikut di channel verifikasi:

```text
VPH-482731
```

atau

```text
verify VPH-482731
```

CdrVerify hanya memproses pesan yang memiliki prefix kode yang benar dan berasal dari guild/channel yang dikonfigurasi.

## Admin commands

```text
/cdrverify status <player|uuid>
/cdrverify code <player|uuid>
/cdrverify reset <player|uuid>
/cdrverify unlink <player|uuid>
/cdrverify force <player|uuid> <discord-id>
/cdrverify reload
```

Permission:

```text
cdrverify.admin
```

Default permission: OP.

`force` tidak akan menimpa link milik akun lain. Lakukan `unlink` secara eksplisit terlebih dahulu jika memang ingin memindahkan kepemilikan akun.

## Storage

```text
plugins/CdrVerify/
├── config.yml
├── messages.yml
├── pending.yml
├── verified-meta.yml
└── audit.log
```

- `pending.yml`: token/sesi yang masih aktif.
- `verified-meta.yml`: metadata keamanan CdrVerify; link Discord resmi tetap dikelola DiscordSRV.
- `audit.log`: aktivitas generate, verify, reverify, reset, unlink, dan force-link.

## Build

```bash
mvn clean verify
```

Output:

```text
target/CdrVerify-0.1.0-SNAPSHOT.jar
```

## Status

`0.1.0-SNAPSHOT` adalah fondasi pertama. Fokus versi ini adalah account-linking flow, persistence, IP/session security, dan Discord channel verification. UI Discord berbasis button/modal dapat ditambahkan sebagai lapisan UX di atas engine yang sama tanpa mengubah model sesi/link akun.
