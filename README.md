**English** | [Русский](README.ru.md)

# <img src="app/src/main/ic_launcher-playstore.png" width="40" height="40" align="absmiddle" alt="Icon"> hiWeb RemoteDesk

**A remote desktop for Windows (and Ubuntu) on your tablet or phone — with a real touchpad, gestures
and the native keyboard of your keyboard case.**

The app is built for the Samsung Galaxy Tab S9 Ultra with a keyboard case: the tablet becomes a thin client
for your PC, and the case works as a genuine laptop touchpad. On a phone (tested on the
Samsung Galaxy S23+ / SM-S916B) the same session is controlled with your fingers, and there is an on-screen
keyboard for typing. Under the hood is a custom RDP client written in Kotlin — no FreeRDP and no third-party
protocol libraries.

---

## Features

### A desktop without compromises
- **RDP with extended security**: TLS, NLA (CredSSP/NTLM), certificate pinning by SHA-256.
- **Two graphics paths**: classic bitmap updates (the default for Windows) and the RDPEGFX pipeline — without it
  you cannot get a picture from GNOME Remote Desktop on Ubuntu; for Windows it is switched on in the PC settings
  ("Graphics pipeline", still experimental). The pipeline supports RemoteFX Progressive, ClearCodec and
  H.264 decoded by the tablet's video hardware: Ubuntu sends it if it can encode video
  (VA-API or NVENC), Windows at its own discretion; otherwise the session runs without H.264.
- **The session keeps running while the app is in the background**: a foreground service stops Android from
  freezing the connection.
- **Recovery after a dropped connection**: if a session was already connected and the link is lost (Wi-Fi, network
  change), the app reconnects by itself for up to 5 minutes, and the last picture stays on screen under a
  "Connection lost" banner. Password rejections and server decisions (sign-out, another user) are never retried —
  this protects your account from lockout.
- **Rotating the screen resizes the desktop**: in portrait the desktop is 1920 tall, without leaving the
  session (Windows keeps the session even when the client reconnects).
- **Windows UI scale** (100–300 %) and **color depth** (16/24/32 bit) — per PC. Windows applies the scale only to a new session: an already open session keeps its own.

### A case touchpad that feels like a laptop's
- Accelerated cursor, left/right/middle button, physical click.
- macOS-style two-finger scrolling, vertical and horizontal (finger jitter is suppressed, a clear diagonal move scrolls both axes), with **inertia** (scroll strength
  and inertia strength are adjustable per PC).
- Pinch to zoom (Ctrl + wheel).
- Three- and four-finger swipes: task view, desktop, switching virtual desktops.
- Drag with "resting finger + second finger", like on a Mac trackpad.
- Axis calibration — the touchpad in the case is mounted sideways relative to the screen.

### Samsung S Pen
- The cursor follows the pen while it hovers, a tap is a click, the pen button is the right mouse button.
- What a stroke on the screen does depends on the Windows cursor under the pen: over text ("I") — selection, at window edges and
  splitters — dragging, elsewhere — scrolling on both axes with inertia. To drag an icon
  or a window by its title bar, hold the pen still and then move. The cursor shape comes from HiWeb Agent (1.9.4+).
- Pen scroll speed (50–200 %, 100 % = same as a finger) is set separately: in the PC form and in the session settings.
- Pen button: right button, eraser, middle button, screen snip (Win+Shift+S) or undo (Ctrl+Z).
- In "Windows pen" mode programs receive a genuine pen: hover, pressure, tilt, button and eraser.
- While the pen is over the screen, palm touches are ignored (can be turned off).

### Native Windows touch
- Instead of imitating a mouse, fingers and the pen are sent to Windows as real touches (RDPEI): scrolling, pinch, the context
  menu on long press, edge swipes, the touch keyboard and pressure-sensitive handwriting are handled by Windows itself.
  The app's own gestures (three to five fingers, scroll and inertia strength, shape-based pen scrolling) do not work in this mode.
- On the first connection to a PC the mode switches on by itself if Windows opened the touch channel. It can be changed in the PC form
  ("Touch input": Auto / Native / Mouse), in the session settings and in the "⋮" → "Touch" menu. If Windows did not open the channel,
  fingers remain a mouse. Windows only.
- In native mode the S Pen has its own setting (PC form and session settings): **Mouse** (hover, right button, cursor-shape
  gestures), **Finger touch** (the pen acts as a plain touch, no hover or pressure) or **Windows pen** (hover, pressure,
  button, eraser; if the PC does not accept a pen — mouse). Without native mode the pen is always a mouse.

### Gestures on the screen itself
In mouse mode, three to five fingers are translated into Windows or GNOME shortcuts: undo/redo, copy/paste/cut,
Alt+Tab, "home", app switcher.

### Keyboard and mouse
- The case's physical keyboard and Bluetooth keyboards work directly.
- **Bluetooth mouse cursor speed** is adjustable per PC (50–200 %); the case touchpad
  is not affected.
- The accessibility service delivers **Win, Alt+Tab and other** shortcuts to the PC that Android would
  otherwise keep for itself. It works only while the session screen is open.
- Key auto-repeat like in Windows: a pause, then fast repeats.
- **Compact session bar** (a pill with icons and a separate red "Disconnect", everything else in the "⋮" menu) and
  a Windows on-screen keyboard button with three modes: **shown / hidden / automatic** (in automatic mode the keyboard
  appears when you tap an input field on the PC and hides when you tap outside it) — requires HiWeb Agent 1.9.1+; without it (and for Ubuntu)
  the button opens the Android keyboard. If the agent on the PC is older than the app, an "Update agent" button appears in the session bar.

### Phone
- **PC list for a narrow screen**: filters as a row of chips, a single column of cards, power actions in the "⋮" menu.
- **Compact session bar at the bottom**, above the keyboard: keyboard (shown / hidden / automatic), microphone,
  camera, location, Win, the "⋮" menu (settings, rotate, Ctrl+Alt+Del, Alt+Tab, agent) and a red "Disconnect".
- **The Android on-screen keyboard** works as PC input: characters, Shift/Ctrl/Alt/Win, arrows, Tab,
  Enter, Backspace go into the session. Win+Tab and other shortcuts work as in Windows.
- Control is by fingers on the desktop (tap, long press, scroll, pinch), like on an iPad;
  touchpad capture and calibration are not needed on a phone and are not switched on.

### Files, clipboard and sound
- **Text via the clipboard** in both directions.
- **Files via the clipboard**: copy a file on the tablet — paste it on the PC (Ctrl+V), and vice versa;
  files from the PC are saved to `Download/RemoteDesk` with a progress indicator.
- **Shared drive**: a tablet folder appears in Windows as a network drive `\\tsclient\Name`, optionally
  read-only. The drive can be attached in the middle of a session.
- **PC sound** plays on the tablet (rdpsnd channel). The "Audio compression" option in the device settings
  enables compressed formats (ADPCM, G.711): the stream is 2–4 times smaller, useful on a slow link.
- **The tablet's microphone** is available in Windows (audin channel, a "redirected" recording device): it is enabled
  in the device settings and with the "Microphone" button in the session menu; it does not work without the Android recording permission.
  With "Audio compression" the microphone is also sent compressed.
- **The tablet's camera** is visible to Windows programs (Zoom, Skype, "Camera") as an ordinary webcam, 720p:
  it is enabled in the device settings and with the Android camera permission; streaming starts only when
  a program on the PC opens it — like a real webcam, with no separate switch.
- **The tablet's location** — Windows programs (maps, weather, browser) get the tablet's coordinates as from a location
  sensor. Enabled in the device settings; streaming is toggled with the "Location" button in the session bar
  (Android asks for the location permission on the first tap); location access must be turned on in Windows.

### One screen — all your computers
- PC cards with a **live screen preview**, search, filters ("online", "offline", by OS) and sorting.
- **Find PCs on the network** with one button: a subnet scan and OS detection by the SSH banner and the RDP reply — no
  credentials needed.
- **Share a PC by QR code**: the button on a card shows a QR with the PC's settings (or sends it as an image); on
  another device "Add by QR code" scans it with the camera or reads it from an image. The password goes into the code only
  with your explicit consent.
- **PC status in plain words**: "online", "someone is working at the PC", "screen locked", "not signed in",
  "sleeping", "hibernating", "restarting", "off".
- **Wake-on-LAN**: connecting to a sleeping PC first wakes it, then waits for the RDP port.

### PC power
Three paths — whichever is available is used:
1. **HiWeb Agent** (the PC is reachable over the local network): shut down, restart, sleep, hibernate — according to the
   modes the PC actually supports.
2. **SSH for Ubuntu**: the command is sent without opening the desktop.
3. **Through an RDP session**: the app signs in, opens the standard Windows power menu and disconnects —
   nothing is installed on the PC.

### HiWeb Agent for Windows (optional, but it is what makes "tablet + PC" a complete whole)
- **Installation from the app**: the exe is delivered to the PC as a temporary drive through an already open session
  and launched in the Run dialog — no downloads, flash drives or email.
- **"Switch to Tablet" from the tray menu**: the PC itself calls the tablet, the session opens, the monitors turn off.
- **Windows notifications on the tablet** while the app is in the background (with filters for Phone Link and your own).
- **Windows touch keyboard on demand**: the app tells the agent whether the tablet has a keyboard and which keyboard
  mode is chosen; the agent shows or hides TabTip (in automatic mode — on a click in an input field).
- **PC folders as drives on the tablet, without screen streaming**: in the "File access…" window (agent tray menu) the owner
  opens one or more folders and chooses "read only" or "read and write" for each. On the tablet they appear
  as separate drives in the system "Files" app and in any app's file picker (the folder icon on a PC card opens
  "Files" right on the PC's drive): browse, download, upload, rename, create folders, delete (to the Windows Recycle Bin).
  Off by default; transfer is encrypted, the agent validates paths, and it will not let writes into a "read only" folder through.
- **PC preview without a session**: the agent sends an encrypted screenshot every 15 minutes.
- **RDP protection**: the agent keeps a log of connections to the PC (who signed in and from which address, wrong passwords),
  notices password guessing by itself (after just 3 failed attempts) and sign-ins from unknown addresses, and warns you with a notification
  in the Windows tray and on the tablet. The attacker's subnet is blocked with one tap in the notification (Windows Firewall;
  Windows asks for confirmation on the PC).
- **Auto-block** (on by default, 5 failed attempts per hour from one subnet): the agent blocks the subnet itself
  if the "blocking helper" is installed on the PC — a small Windows service that is installed once (one administrator-rights
  prompt) and then blocks without prompts, even when you are not at the PC. The threshold and the switch are in the "Connection
  protection" window on the PC and in the app. Your own and known addresses are never blocked.
- **Tablet notifications for every failed sign-in attempt and every block** — on all connected devices
  (over the local network and from a session). Attempts from one subnet update a single notification (a "until block: N" counter).
- **Whitelist**: known addresses and whole subnets are not checked or blocked. The address the tablet connected from is added
  by the agent itself (the address or the whole subnet — your choice), the rest is added by hand from the tablet or the PC;
  "It's me" on a threat adds the address, "Whole subnet" adds the subnet. The "Whitelist" mode is stricter than normal: an alert on any
  sign-in attempt from an address outside the list, from the very first attempt rather than after several failures.
- **Tray icon**: a laptop silhouette matching the taskbar theme, with the tablet's battery.
- **Agent windows and menus in the style of the session bar** (pill buttons, a red "Remove"/"Block"): light on white
  or dark — following the Windows "Default app mode" setting, theme changes are picked up on the fly.

---

## Getting started

1. Download the APK from the [latest release](https://github.com/denmedia/hiweb-remotedesk/releases/latest) and install
   it on your tablet or phone (Android 13 and newer).
2. On the PC enable Remote Desktop: "Settings → System → Remote Desktop"
   or use the `tools/enable-rdp.ps1` script from the repository (copy it to the PC and run
   it as administrator).
3. In the app tap **"Search"** and add the PC found — or enter the address manually.
4. For Win / Alt+Tab shortcuts enable the "RemoteDesk: key forwarding" service in Accessibility settings.
5. Optionally install **HiWeb Agent**: the "Install agent" button in the session menu.

On first launch the touchpad asks you to swipe a finger along the axes once — this is how the app learns how the
touchpad in the case is oriented.

**Updating the app.** The "⋮" menu on the main screen → "Check for update": the app looks at the latest
release on GitHub and, if it is newer, offers to download the APK to the Downloads folder. You install the file
yourself; tapping the "ready" notification opens the Downloads folder.

---

## Technical documentation

In brief; the lasting details of the project's design are in [CLAUDE.md](CLAUDE.md) (in Russian), the agent protocol is in
[windows-agent/PROTOCOL.md](windows-agent/PROTOCOL.md).

### Stack

| What | With |
|---|---|
| Language, UI | Kotlin 2.2, Jetpack Compose (Material 3) |
| SDK | `minSdk 33`, `targetSdk 35` (no higher: on Android 16 an app with `targetSdk` ≥ 36 has its screen orientation ignored on tablets), AGP 9.4.1 |
| RDP protocol | entirely custom: `app/src/main/java/com/hiweb/hiwebremotedesk/rdp/` |
| SSH (Ubuntu power) | JSch 2.28.8 |
| QR codes | ZXing (generate and read), CameraX (scanner) |
| Windows agent | C# 5, .NET Framework 4.8, version in `windows-agent/VERSION` (1.11.0) |

### Layout

```
app/src/main/java/com/hiweb/hiwebremotedesk/
├── rdp/          RDP client: RdpClient, Bytes, Crypto, CredSsp, Ntlm,
│                 ClipboardChannel, DriveChannel, SoundChannel, AudinChannel, EcamChannel,
│                 DynamicChannel, GfxChannel/Zgfx/RfxProgressive, DisplayControlChannel…
├── agent/        HiWeb Agent on the tablet: AgentLink (channel), AgentHub (TCP),
│                 SwitchCommand (UDP), AgentInstaller, SecureLines, PcNotifications,
│                 PcDocumentsProvider/PcFiles/PcDocId (PC folders as drives in "Files")
├── data/         Device + DeviceRepository, SecretBox, Network (scanner/OS detector),
│                 LinuxSsh, WakeOnLan, PcState/PcStatus, ThumbnailStore, SafDriveStorage,
│                 AppUpdate/UpdateManager (manual update check via GitHub Releases)
├── ui/           HomeScreen, Dialogs, HomeViewModel, Components, theme
├── RemoteActivity / RemoteView     session screen and rendering
├── TouchpadEngine / TouchpadGestures / TouchscreenGestures / TwoFingerClassifier / ScrollFling
├── KeyCaptureService               accessibility service
├── SessionService                  foreground session service
└── AudioPlayer, MicSource, CameraSource, RemoteFileDownloader, ScanCodes…
windows-agent/    agent sources (C#) and PROTOCOL.md
tools/            enable-rdp.ps1, create-test-user.ps1
app/src/test/     unit tests (protocols, gestures, OS detection, crypto)
```

### Building

There is no JDK in PATH — point to one first:

```powershell
$env:JAVA_HOME = "D:\Program Files\Android\Android Studio\jbr"
./gradlew.bat --console=plain -q :app:testDebugUnitTest :app:assembleDebug
```

The APK: `app/build/outputs/apk/debug/app-debug.apk`. A debug build is installed on the tablet over wireless
adb: `adb install -r -t app-debug.apk`.

The agent (C# 5 only):

```powershell
windows-agent\build.cmd
```

The script puts `HiWebAgent.exe` and `version.txt` into `app/src/main/assets/agent/`. For the app to offer an
agent update, raise `windows-agent/VERSION`, rebuild the agent, then the APK.
Checks of the RDP protection rules (addresses, subnets, password-guessing detection) and file access: `windows-agent\tests\test.cmd`.

A release is a GitHub release with the tag `v<version>` and the APK `HiWebRemoteDesk-<version>.apk` — the in-app
update check relies on this naming.

### Protocol and ports

| Channel | Port / transport | Purpose |
|---|---|---|
| RDP (Windows) | TCP 3389, TLS + NLA | session, graphics, input |
| RDP (Ubuntu) | TCP 3390 | GNOME Remote Desktop (requires RDPEGFX) |
| Static channels | inside RDP | `cliprdr` clipboard/files, `rdpdr` shared drive, `rdpsnd` sound |
| Dynamic channels | inside RDP (drdynvc) | `HiWeb::Agent`, cursor, `RDPEDISP` desktop resize, `AUDIO_INPUT` microphone, `RDCamera_Device_Enumerator`/camera, `Microsoft::Windows::RDS::Location` location (MS-RDPEL), `Microsoft::Windows::RDS::Input` touch and pen (RDPEI) |
| Agent ↔ tablet | TCP 47853 (can be changed in the PC settings), JSON + HMAC | PC status, power, preview, files, "switch" |
| Tablet invitation | UDP, the same port as the agent's TCP | the signed "Switch to Tablet" command |
| Wake-on-LAN | UDP 9 and 7 | waking a sleeping PC (broadcast) |

PDU layouts are taken from the FreeRDP sources; byte structures are in `rdp/Bytes.kt`.
Channel messages are split into 1600-byte fragments.

### Device format

`Device`: `id`, `name`, `host`, `port`, `os` (`WINDOWS`/`UBUNTU`), `username` (`DOMAIN\name`), password,
`certSha256` (pinned certificate), `useNla`, `scalePercent`, `scrollLevel`/`inertiaLevel`,
`fileTransfer`, `microphone`, `camera`, `drive*` (shared drive), `colorDepth`, `wakeMac`.
Stored in SharedPreferences (`devices`); passwords only in encrypted form.

### Security

- Passwords are encrypted with AES-GCM using an Android Keystore key (`SecretBox`); no plaintext password on disk.
- The PC's certificate is pinned on the first connection; a certificate change aborts the connection.
- The SSH host key is remembered on first sign-in and checked before the password is sent.
- Local-network commands are signed with HMAC-SHA256; screenshots are encrypted with AES-256-CBC.
- The "Switch to Tablet" entry is protected by a signature, a clock window (±5 min) and a one-time `nonce`.
- Subnet blocking changes the firewall, so it goes either through a Windows administrator-rights prompt on the PC (manually;
  the elevated copy of the agent re-validates the subnet and does not block local addresses or ranges wider than /16), or through
  the helper — a Windows service in `Program Files` installed with a single UAC prompt. The helper listens on a named pipe
  closed to everyone except the account that installed it (and to the network), and on request only adds and removes rules
  for public subnets no wider than /24 (IPv6 — /64), no more than 200 new ones per hour; it is removed from the protection window or when the agent is uninstalled.

### Tests

`app/src/test/` — JVM tests for protocols and pure logic: parsing of cliprdr/rdpdr/rdpsnd/audin/rdpecam/display-channel PDUs,
the cursor decoder, touchpad and touchscreen gestures, scroll inertia, OS detector, crypto, PC statuses, SecureLines,
agent version comparison, the file client and PC drive identifiers (with reference encryption values from the .NET agent).
Agent: `windows-agent\tests\test.cmd` (RDP protection rules and file access). Instrumented tests (`connectedAndroidTest`) reinstall the app
and delete saved PCs — do not run them on a working tablet.

### Limitations

- The client targets Windows and Ubuntu with GNOME Remote Desktop; other RDP servers are not guaranteed.
- The tablet's microphone, camera and location are redirected only into Windows: GNOME Remote Desktop does not expose these devices to a session.
- The camera is delivered as MJPG 1280×720 (the one format verified on a PC): a picture above 720p is not possible.
- The Windows touch keyboard, notifications and screenless file access require HiWeb Agent (1.11+ for files); without it the session is fully usable.
- PC drives are served through a `DocumentsProvider`: apps that need an ordinary `/storage/…` path will not open them. Not all shells
  show such drives in the main tree of their file manager (the file picker and Google's "Files" app do).
  Windows notifications reach the tablet while an RDP session is open and the app is in the background. If they do not arrive,
  the agent's tray menu has "Check notification forwarding…": it shows a test notification and, step by step, where it
  got stuck.
- RDP protection works only on Windows with the agent. A threat notification reaches the tablet when it is on the same network
  as the PC or in a session; everything else the tablet receives on the next connection (the log and threats are re-sent).
  A disabled Windows Firewall does not apply blocking rules — the agent offers to turn it on (when installing the helper
  and on a manual block). Auto-block without the installed helper does not block the subnet: the tablet gets a notification
  about it (no more than once an hour per subnet).
- On a tablet (with a physical keyboard or touchpad case) the Android system keyboard is not shown in a session — input comes
  from the keyboard and touchpad; the keyboard button in the session bar opens it manually
  or on an input field on the PC ("automatic" mode).
  The on-screen keyboard from the "Tablet" setting is needed only for a tablet without a physical keyboard.
