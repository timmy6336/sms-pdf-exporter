# SMS to PDF Exporter

A small native Android app: pick one of your text message conversations,
choose "all messages" or a date range, and it saves that conversation as a
PDF — in order, with each message's sender and timestamp — directly to
your phone. Nothing is uploaded anywhere; the app doesn't even request the
Internet permission.

## How it works

- **MainActivity** lists your SMS conversations (grouped by thread), newest
  first, with a name/number, last message preview, and message count.
- **ConversationDetailActivity** lets you pick "All messages" or a date
  range (two date pickers), then "Export to PDF." You're prompted to pick
  where to save it (Android's standard "Save As" picker — no storage
  permission needed), and afterward you can open or share the PDF directly.
- **SmsRepository** reads the built-in Android SMS content provider
  (`content://sms`) — the same store the Messages app itself reads from.
- **PdfExporter** renders the conversation using Android's built-in
  `PdfDocument`/`Canvas` APIs (no third-party PDF library), paginating
  automatically as messages fill each page.

## Scope / limitations

- **SMS only** — not MMS (photos/group messages), RCS, or iMessage. Those
  live in different, less standardized stores; this covers plain text
  messages, which is what was asked for. Extending to MMS is a reasonable
  follow-up if you want photos/group threads included too.
- Only works on Android (SMS is an Android/Telephony concept — there's no
  equivalent provider on iPhone).
- Requires Android 8.0 (API 26) or newer — covers the overwhelming
  majority of phones in use today.
- Only you can read your own SMS: the app requests `READ_SMS` (and
  `READ_CONTACTS`, to show names instead of just numbers) at runtime, and
  nothing else. No `INTERNET` permission is declared at all.

## Building the APK

This is a standard Gradle-based Android Studio project — there's no build
server or hosting involved, everything compiles on your own machine (or in
this sandbox, if its network policy allows it — see below).

### Option A — Android Studio (recommended, easiest)

1. Install [Android Studio](https://developer.android.com/studio) if you
   don't have it — it bundles everything else needed (the Android SDK,
   build tools).
2. **File → Open**, select this `SmsPdfExporter` folder, let Gradle sync.
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. Android Studio shows a "locate" link when it finishes —
   `app/build/outputs/apk/debug/app-debug.apk` is the file.
5. Copy that APK to your phone (email, USB, cloud drive — doesn't matter)
   and tap it to install. Android will ask you to allow "install unknown
   apps" for whatever app you opened it from — that's expected for any APK
   that isn't from the Play Store.

A debug build (what "Build APK(s)" produces) installs and runs exactly
like any other app; it's "unsigned" only in the sense that it uses a
generic Android-Studio-generated debug key rather than a Play Store
release key, which doesn't matter for sideloading onto your own phone.

### Option B — command line, if you have the Android SDK installed

```bash
./gradlew assembleDebug
# APK lands at app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Option C — download a prebuilt APK (easiest, no install needed)

A GitHub Actions workflow (`.github/workflows/build-apk.yml`) builds the
APK on every push to `main` and publishes it two ways:

- **Stable link, always the newest build**: open this repo's
  [Releases page](../../releases/latest) on your phone and download
  `app-debug.apk` directly from the browser.
- **Per-run artifact**: the [Actions tab](../../actions) lists each build;
  open one and download `app-debug-apk` from its summary page (requires
  being signed into GitHub).

Either way, once it's downloaded, open the file on your phone to install
it — Android will prompt you to allow installing from whatever app you
opened it with, which is normal for any APK that isn't from the Play
Store.

