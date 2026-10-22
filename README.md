# gsm2sip-selfhost - a "device-in-itself" phone gateway for old rooted Qualcomm phones

> 🇷🇺 **[Русская версия →](README.ru.md)** (fuller, with the project's own notes) ·
> 🇬🇧 English (this file). Original project README: [`README.upstream.md`](README.upstream.md).

> 👉 **New here?** A plain-language, from-scratch step-by-step guide (flash, module, softphone,
> port forwarding) is in [`docs/КАК-НАСТРОИТЬ.md`](docs/КАК-НАСТРОИТЬ.md) (Russian).

## Documentation map

- **For users:** [`docs/КАК-НАСТРОИТЬ.md`](docs/КАК-НАСТРОИТЬ.md) - the single page that's enough
  to set everything up from scratch (Russian).
- **Reference:** [gateway app settings](#gateway-app-settings) ·
  [baresip client](docs/client-baresip.md) · [internet and security](docs/internet-security.md)
  (VPN as a fallback - [`docs/vpn-wireguard.md`](docs/vpn-wireguard.md)).
- **For developers:** [building from source](docs/build.md).

A cheap old Android phone with your SIM sits at home on a charger and Wi-Fi. The incoming
**voice calls and SMS** that arrive on its number are relayed to your phone over the internet -
wherever you are. **No PC, no Asterisk, no external server:** the SIP server is built into the
app on the gateway phone itself.

```
Caller ──GSM──► Gateway phone (SIM + gsm2sip)                 Your phone (softphone)
                 ├─ built-in SIP registrar  :5062  ◄──REGISTER── anywhere
                 ├─ GSM ↔ SIP bridge (answers the call itself)
                 ├─ incoming SMS → SIP MESSAGE ─────────────────► softphone chat
                 └─ RTP (G.711) ────────────────────────────────► two-way audio
                                                       ◄───────── SMS reply / voice
```

This is **track B** of the larger "переадресация" project. Track A (a fork of
[android-voice-sip-agent](https://github.com/sparkcyf/android-voice-sip-agent), **no root**,
Android 13+) lives in the separate `voice-sip-agent-selfhost` repository. Which track fits a
given phone is decided by the [triage script](#hardware-requirements).

---

## Who it's for

Your home number (SIM) anchors your bank, government services, 2FA codes, and calls from family
and services - carrying it around is both inconvenient and risky. This gateway keeps the SIM
**at home** and delivers its calls and SMS to you over the internet, on any phone, wherever you
are.

- **Vacation / business trip.** No roaming, no missed incoming on your home number, no changing
  numbers. On the road your phone carries a local eSIM or a second SIM for data only; the home
  SIM stays in the gateway at home.
- **Long relocation / living abroad** (expat, student, rotational work). Your home number stays
  "alive" and tied to your accounts while you are in another country: bank calls and SMS codes
  arrive as before.
- **Safety on the street.** If your phone is stolen or snatched in an unfamiliar city, the home
  SIM **isn't in it** - it's at home. The thief gets no access to your number, can't intercept
  SMS codes, can't reset passwords by SMS, and can't reach SIM-bound accounts or your bank. The
  stolen phone holds only a local data eSIM or an "empty" second SIM with no important bindings.
- **Privacy and control.** A "device-in-itself": the SIP server lives inside the gateway phone,
  audio and messages go straight to your softphone - no clouds, no third-party servers, nothing
  leaves to anyone else.

**For:** travelers and digital nomads, expats, rotational workers, students abroad; anyone who
keeps an "anchor" number for banks and government services and doesn't want to carry it; and
anyone who values privacy (no external server).

---

## What already works

✅ **Two-way audio confirmed on 2026-10-22** on a Samsung S4 mini GT-I9190 (`serrano3g`),
LineageOS 16 (Android 9), in built-in registrar mode:

- Incoming GSM call to the gateway's SIM → the call rings on the softphone, audio flows **both
  ways**, hanging up from either side ends the call cleanly.
- Incoming **SMS** → arrives in the softphone chat, Cyrillic decodes correctly.
- **Outgoing SMS** from the softphone → goes out over GSM, the network confirms delivery
  (`delivered, deliveredOk:1`).
- **Reply** to an incoming SMS in the same chat thread → delivered (v1.3.11 fix, see below).
- Self-recovery: the gateway starts itself after the phone reboots (Magisk).

Not done yet: outgoing **calls** from the far phone over the gateway's SIM; wideband audio
(currently G.711, narrowband); SIP authentication for the registrar
(see [security](#going-to-the-internet-and-security)). The internet path (public IP, forwarding,
STUN) is written but **not yet tested live** - LAN only.

---

## How this fork differs from upstream

Stock [gsm2sip](https://github.com/pulpoff/gsm2sip) registers to an **external** SIP server
(Asterisk, etc.). This fork adds a **built-in registrar** mode - the idea carried over from
track A:

- the app accepts `REGISTER` on `:5062` itself, remembers the softphone's address, and routes
  the incoming GSM call and incoming SMS **straight to it**;
- the mode is **on by default** (`builtin_registrar=true`), no external server needed;
- outgoing SMS from the softphone is accepted both as `text/plain` and inside a **CPIM**
  envelope (RFC 3862), and **IMDN** receipts (RFC 5438) are acknowledged without a spurious send;
- the `From` of a delivered SMS carries the registrar port (`:5062`) so the client's **reply**
  comes back to the gateway instead of vanishing on `:5060` (v1.3.11 fix).

The rest (GSM↔SIP bridge, RTP, the Magisk module with permissions and ALSA mixer control) is
from upstream.

---

## Architecture: "device-in-itself"

There is no external server. All SIP runs on the gateway phone itself.

- The softphone registers **straight to the gateway phone** (its IP, port `5062/UDP`).
- An incoming GSM call → gsm2sip answers it, sends `INVITE` to the **first registered** softphone,
  and brings up a peer-to-peer RTP bridge. No Asterisk/PC in the path.
- To work "while traveling" the gateway phone must be reachable from the internet - a public IP
  with port forwarding, or a VPN (see [below](#going-to-the-internet-and-security)).

### Where things live

"All the logic is in the APK" (`com.callagent.gateway`): registrar, bridge, RTP. **But** the app
needs system permissions and the audio restrictions lifted - that is what the **Magisk module**
does. So the "server part" = APK + Magisk module, shipped together in one `gateway-magisk.zip`.

| Component | Where | What it does |
|---|---|---|
| SIP registrar + bridge + RTP | APK `com.callagent.gateway` | all gateway logic |
| Magisk module `sip-gsm-gateway` | `/data/adb/modules/sip-gsm-gateway/` | installs the APK as a priv-app, grants permissions, lifts audio restrictions |
| `privapp-permissions-gateway.xml` | → `/system/etc/permissions/` | `CAPTURE_AUDIO_OUTPUT`, `MODIFY_PHONE_STATE`, `CALL_PRIVILEGED`, `BIND_INCALL_SERVICE`… |
| `system.prop` | module | `voice.*.conc.disabled=false` (VoIP audio during GSM), Fluence off |
| `tinymix` / `tinymix32` | → `/data/local/tmp/` | ALSA mixer control (audio route on MSM8930) |
| `install.sh` / `service.sh` | module | installs the APK, hides PermissionController, re-grants runtime perms on each boot |
| Settings (codec/STUN/…) | `shared_prefs/gateway.xml` | config |

> ⚠ **The registration table lives in process RAM.** After the gateway reboots (or the app
> restarts) the bindings are gone, and the softphone won't get a call until it re-registers. So
> set a **short registration interval** on the client (120–300 s). It also refreshes the NAT
> binding over the internet.

---

## Hardware requirements

- **Qualcomm**, NOT Exynos (no suitable call-audio path on Exynos - verified by the gsm2sip author on an S10e).
- **LineageOS + Magisk** (stock usually lacks the needed audio path; on the S4 mini `incall_music`
  appears specifically on LineageOS).
- A voice SIM, stable Wi-Fi, permanent charging.
- This should be a **dedicated "duty" phone** with no personal data: it lives rooted and reachable.

Confirmed with a live call:

| Phone | Codename | Chip | OS |
|---|---|---|---|
| Xiaomi Poco X3 NFC | `surya` | SM6150/SM7150 | the gsm2sip author's reference |
| Samsung S4 mini GT-I9195 | `serranolte` | MSM8960 | LineageOS 16 (gsm2sip author) |
| **Samsung S4 mini GT-I9190** | `serrano3g` | MSM8930/WCD9304 | **our unit, two-way audio 2026-10-22** |

**Triage script** (quick elimination without installing) - `triage/check-phone.sh` in the larger
project: connect the phone over USB (debugging on), run `bash check-phone.sh` → "CANDIDATE/NOPE" +
root signals + the recommended track (A/B).

---

## Flashing a clean phone

Example: S4 mini GT-I9190 (`serrano3g`). Root and flashing are common to any track B.

| File | What | Source |
|---|---|---|
| `Magisk-v30.7.apk` | root on top of LineageOS | github.com/topjohnwu/Magisk |
| `gateway-magisk.zip` | gsm2sip: APK + perms + tinymix | our build (`./build.sh release`) |
| `twrp-3.7.0_9-0-serrano3gxx.img` | recovery | dl.twrp.me/serrano3gxx |
| `lineage-16.0-…-serrano3gxx.zip` | ROM (Android 9) | lineageos / mirrors |

No GApps needed. Steps (modes via adb, Odin is a GUI):

1. **Download mode:** `adb reboot download` (or Vol Down + Home + Power).
2. **TWRP via Odin:** pack the `.img` into a `.tar` (`tar -H ustar -cf recovery.tar twrp-…img`),
   Odin3 → **AP** slot = this `.tar` → **Start**.
3. **Straight into recovery** (so stock doesn't overwrite TWRP): `adb reboot recovery`.
4. **In TWRP:** Wipe (data/cache/system) → install **LineageOS 16** (`adb sideload <ros.zip>`),
   reboot, do first-time setup.
5. **Root:** install `Magisk-v30.7.apk`, grant root.

Hardware checkpoint (before anything else): `tools/check-device.sh` (needs root) - inspects the
HAL mixers and the capture path. No path → track B is closed on this phone by hardware.

---

## Installing the server part (Magisk)

The phone is already flashed with LineageOS 16 + Magisk; you only install our software. There is
no external server.

1. **Build the module** (on a PC): in the repo root, `./build.sh release` (JDK 17, Android SDK) →
   produces `gateway-magisk.zip` - the APK, permissions and tinymix together. More, plus build
   gotchas, in [`docs/build.md`](docs/build.md) (Russian).
   > ABI: `tinymix` is built for both architectures; `install.sh` places the right one by
   > `ro.product.cpu.abi`. The S4 mini is 32-bit (`armeabi-v7a`).
2. **Install the Magisk module:** copy `gateway-magisk.zip` to the phone → Magisk → Modules →
   install from file → **reboot**. The module installs the APK as a system priv-app, grants perms,
   lifts audio restrictions, places `tinymix`, and hides PermissionController.
3. **Default phone app:** Settings → Apps → Default apps → Phone → **gsm2sip**. Without this the
   app won't answer GSM calls by itself.
4. **Check permissions** (optional, adb+root):
   `dumpsys package com.callagent.gateway | grep -E "CAPTURE_AUDIO_OUTPUT|RECORD_AUDIO"` - should be
   `granted=true`. The Magisk log tag `GatewayMagisk` traces the whole run.
5. **Open the app, configure it** (below). In "device-in-itself" mode no SIP server/login/password
   is needed.

**Updating the APK without re-flashing the module:** `adb install -r app-release.apk` + reboot (on
the next boot `service.sh` syncs the APK into priv-app). The signature must match the installed one
(we sign with the debug key on purpose, so the signature stays identical).

---

## Gateway app settings

Opened via the gear at top right. For "device-in-itself" mode:

| Item | Value | Why |
|---|---|---|
| Built-in registrar | **on** (default) | the app accepts REGISTER on `:5062` itself |
| SIP server / port / user / pass | leave as-is | unused in registrar mode |
| Own Number | anything (we used `100`) | in registrar mode it does **not** affect softphone selection: the gateway calls the FIRST live registration |
| Codec | G.711 (PCMA/PCMU) is safer on a narrow link; G.722 is wider | the call log showed `pt=8` (PCMA) |
| **STUN (`use_stun`)** | **off for LAN, on for internet** | off → advertises `localIp`; on → public IP (see [internet](#going-to-the-internet-and-security)) |

The registrar port **5062** is hardcoded (`localPort`). RTP range - UDP **30000–40000** (even),
`RTP_PORT_MIN/MAX` in `CallOrchestrator.kt`.

---

## Recommended client - baresip

The far phone needs a SIP softphone. We recommend
**[baresip](https://f-droid.org/packages/com.tutpro.baresip/)** (`com.tutpro.baresip`, F-Droid):
a free libre client that **always** sends page-mode `MESSAGE` (RFC 3428) and holds several
accounts. **Linphone 6.2.x did not work out** - its new UI won't send a plain page-mode MESSAGE to
an arbitrary number (built around Flexisip/encrypted chat), so outgoing SMS never left it.

Quick account setup: ⋮ → "Accounts" → the "SIP URI or New account" field →
`sip:100@<gateway-IP>:5062` → "+" → enable the **"Register"** toggle (interval 900).
No password/proxy/username needed.

> ⚠ **The `:5062` port is mandatory right in the AoR** - otherwise both REGISTER and MESSAGE go to
> `:5060`, where nothing is listening.

Full client guide (two SIMs, SMS replies, alternatives, gotchas) -
[`docs/client-baresip.md`](docs/client-baresip.md) (Russian).

---

## Going to the internet, and security

To reach the gateway "from the road" it must be reachable from the internet. **The main path is
direct access:** the gateway phone behind a router with a **public (non-CGNAT) IP**, the softphone
anywhere. In short:

1. **DHCP reservation** for the gateway's LAN address by MAC - so it doesn't change.
2. **UDP forwarding** on the router: `5062` (SIP) and `30000–40000` (RTP) to the gateway's LAN
   address. RTP must be **1:1, same port numbers** (no translation, or audio goes to the wrong
   place). The registrar port `5062` is hardcoded.
3. **Enable STUN** in the app → the gateway puts its public IP in Via/Contact/SDP.
4. Point the client at `<public IP or domain>:5062`.

**The fallback is a VPN** (WireGuard) if there is no public IP or you don't want to expose SIP; the
gateway phone can be its own VPN server. On track B the VPN still has a reply-path bug (registration
doesn't go green yet) - see [`docs/vpn-wireguard.md`](docs/vpn-wireguard.md).

> ⛔ **Security - must read.** In built-in registrar mode there is **no authentication yet** (empty
> password). An open `:5062` on the internet is a toll-fraud risk: anyone can register as "100" and
> intercept calls, or make the gateway send/dial over GSM (real money). A password on
> REGISTER/INVITE and automatic source banning (like Asterisk + fail2ban) are **planned but not
> implemented yet**; until then an **IP allow-list** on the router (or a VPN) is required. A
> non-standard port is camouflage, not protection.

Full step-by-step (direct access, security, VPN as a fallback) -
[`docs/internet-security.md`](docs/internet-security.md) (Russian).

---

## Building from source

```bash
cd gsm2sip
./build.sh release        # → gateway-magisk.zip (APK + perms + tinymix)
```
JDK 17 and the Android SDK are required. On Windows there are gotchas (a 32-bit JVM in `JAVA_HOME`,
backslashes in `local.properties`, a failing `lintVital`, MSYS path conversion) - all in
[`docs/build.md`](docs/build.md) (Russian).

---

## Honest status and risks

- Audio on `serrano3g` in "device-in-itself" mode is **confirmed** (2026-10-22, two-way). Needs a
  proper stability retest under load (planned).
- Outgoing/incoming SMS and replies - confirmed with baresip (above).
- **The internet path (public IP, forwarding, STUN) is not yet tested live** - LAN only. That's the
  next step when the phone moves to a location with a public IP.
- **Debug build:** verbose SIP logs (`SipWire`) are on and the RTP silence timeout is raised to 1
  hour (`RTP_TIMEOUT_MS` in `RtpSession.kt`). Revert before a "release" (drop the log, timeout → 30 s).
- **The main production risk** is an open `:5062` with no auth. Registrar SIP authentication is a
  deliberate TODO; until then, only VPN or an IP allow-list.

### Rollback point

git tag **`v1.3.9-serrano3g-twoway`** (commit `e33f615`): `git checkout v1.3.9-serrano3g-twoway`.
Current release - **1.3.11** (versionCode 438): two-way SMS in registrar mode.

---

## Support the project (donate)

Non-commercial, distributed directly (APK), not via app stores. If it's useful to you, you can
**voluntarily** support development:

- **DonationAlerts:** [donationalerts.com/r/hinducoder](https://www.donationalerts.com/r/hinducoder)

> The donation is **entirely voluntary** and changes nothing: all features work without paying and
> the source is open (MIT). Not a purchase or subscription.

---

## License and credits

A fork of [**pulpoff/gsm2sip**](https://github.com/pulpoff/gsm2sip). License - **MIT** (see
[`LICENSE`](LICENSE), © 2026 pulpoff). Our changes are distributed under the same MIT terms. The
original README is preserved as [`README.upstream.md`](README.upstream.md).

Thanks to the gsm2sip author for the GSM↔SIP bridge, the Magisk module, and ALSA mixer control on
Qualcomm - without it track B would be impossible.

**Credits.** The code was written by Claude (Anthropic) to the owner's tasks; the owner set the
tasks and tested every build on a live device.
