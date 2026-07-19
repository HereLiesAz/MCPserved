# Google Play — store listing copy

Ready-to-paste text for the Play Console listing. Written to match the app's
actual behavior — local, user-authorized, accessibility-for-control — so it lines
up with the permissions declaration and won't read as overclaiming.

## Main store listing

**App name** (max 30 chars)
```
MCPserved: local AI control
```
*(27 chars. Alternatives: `MCPserved` · `MCPserved — AI device control`.)*

**Short description** (max 80 chars)
```
Let an AI operate your phone — locally, over adb or a paired app you control.
```
*(76 chars.)*

**Full description** (max 4000 chars)
```
MCP Served. Your context, securely served.

MCPserved lets an AI assistant you run — on your own computer — read your screen and tap, type, and swipe on your Android phone. It's the local, user-authorized way to give a model hands on your device.

No relay. No cloud. No account. Control travels a connection you set up yourself: a USB cable, or adb over your own Wi-Fi. The app opens no internet connection and sends your screen to no server.

TWO WAYS TO CONNECT
• Quick connect (adb): turn on USB debugging and a model can drive the phone right away — no pairing — using Android's own input, UI-dump, and screenshot tools.
• Paired app (richer): pair once by QR to unlock the accessibility backend — a semantic view of the screen, real text entry, per-app permissions, and a notification mirror.

YOU STAY IN CONTROL
• Nothing happens until you enable the service, pair a client, arm the app, and grant specific apps.
• Grants are per app and per scope — observe, interact, type, launch, shell — revocable, and expiring by default. An empty grant list leaves the app unable to touch anything.
• Every session is time-limited and shown in an ongoing notification with a Stop button. Every action is logged while a session is open.
• Rotate the pairing key to cut a client off entirely.

ACCESSIBILITY SERVICE
MCPserved uses Android's Accessibility Service to read the screen and dispatch input on your behalf — that is the app's core function. It acts only for the apps you grant, only during a session you start, and only over the local connection you set up. You'll see a full disclosure and be asked to agree before anything is enabled.

BUILT TO BE TRUSTED
• Sealed, authenticated messages (X25519 pairing, ChaCha20-Poly1305) — even over the local link.
• The control channel binds to 127.0.0.1 and dials nothing outward.
• Keys are stored encrypted on the device and never leave it.
• No analytics. No accounts. Nothing collected.

WHO IT'S FOR
Developers, power users, and anyone building agentic workflows who wants an AI to operate a real Android device — without handing that device to the cloud.

REQUIREMENTS
MCPserved works with a companion desktop server (a free, open-source command-line tool) and a device with USB debugging or adb-over-Wi-Fi enabled. It's built for people comfortable with adb.
```

**Release notes** (max 500 chars)
```
First release. Local Android control for AI agents: drive the phone over adb, or pair the app for accessibility-backed control with per-app grants. Time-boxed sessions, an ongoing notification, and a full in-app disclosure. No network, no accounts, nothing collected.
```

## Store settings

- **Category:** Tools
- **Tags:** developer tools, automation, accessibility
- **Content rating:** complete the questionnaire honestly → expect *Everyone*.
- **Target audience:** adults; not designed for children.
- **Contact email:** your support email (required).
- **Privacy policy URL:** **required** — see below.

## Graphics checklist

- **App icon:** 512×512 PNG — the gem mark.
- **Feature graphic:** 1024×500 PNG — the logo lockup on a brand background (dark plum or peach).
- **Phone screenshots:** 2–8, 16:9 or 9:16. Suggested shots: the disclosure screen, the Status screen (accessibility / notifications / service), the Grants screen, an active session with the ongoing notification visible.
- **Optional promo/demo video:** Play typically wants one for accessibility use — see the shot list in `play-permissions-declaration.md`.

## Data safety form

- Data collected: **none.** Data shared: **none.**
- No account, no analytics. Screen content read via accessibility is relayed only to the paired local client over the loopback/adb connection and is not persisted or transmitted off-device by the app.
- Pairing keys are stored in encrypted storage on the device and never leave it.

## Still needed before submission

- **Privacy policy page** at a public URL (required by Play, and doubly so with accessibility + sensitive permissions). Content mirrors the Data safety section above.
- **Accessibility / permissions declaration** in the Console — text is in `play-permissions-declaration.md`.
- **Demo video** (shot list in `play-permissions-declaration.md`).
```
