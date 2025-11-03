<p align="center">
  <img src="images/logo.svg" alt="IntentBridge logo" width="120">
</p>

# IntentBridge

**IntentBridge** lets you open profile-specific apps from another Android profile — no more copy-pasting URLs between profiles.

For example, if your current profile doesn't have Google Maps installed, tapping a Maps link normally opens a browser instead.
IntentBridge bridges that gap by relaying the link to the profile where Maps (or another target app) is installed.

This is useful if you isolate Google Play Services using GrapheneOS's *Private Space*, or want to open links from your *Work Profile* in your *Personal Space's* default browser.
See the [Screenshots](#screenshots) below for a quick glimpse.

## Features

* Relays Google Maps, YouTube, Mail, Phone, general web links, and share/save files between profiles.
* Resolves Google short links in any profile.
* Configurable per-link-type routing between profiles.
* Minimal interaction — open via a single notification tap.
* Uses TLS for a secure local channel.
* Fully offline — no external server required.

## How It Works

Android profiles are isolated by design, with only limited shared interfaces (e.g., clipboard).
IntentBridge uses *localhost* sockets — a channel shared across profiles — to relay intents securely between them.

When a link is opened in one profile:
1. IntentBridge sends it through the local TLS socket to the counterpart in the other profile.
2. Because background apps can't directly start activities, the receiving profile shows a notification.
3. Tapping the notification opens the link with the correct app.

The system is secure by design:
* TLS ensures encrypted, mutually authenticated communication (`needClientAuth`).
* Certificates are trusted on first use and pinned for future sessions.

## Building

IntentBridge provides a Nix-based development environment.

```bash
direnv allow      # or: nix develop
gradle assembleDebug
```

To install:

```bash
gradle installDebug
```

Or manually transfer the APK to your device.

## Setup

1. **Install in both profiles** you want to bridge.

2. **Open each app once** to:

   * Grant necessary permissions.
   * Generate certificates.
   * Start the background listener.
     A persistent "silent" notification will appear — required to keep the service active (you can hide it in system settings).

3. **Configure link handling:**

   * Set IntentBridge as the default handler for the relevant links in the *source* profile (via button in UI).

     * Example: To relay Maps links from *Personal* to *Private Space*, set IntentBridge as the default Maps handler in *Personal*.
     * Example: To relay all web links from *Private* to *Personal*, set it as the default browser in *Private*.

   * In the app's UI, toggle which link types should be bridged and in which direction.

Once configured, IntentBridge securely relays supported links between profiles with a single tap.

## Future Directions

* Support for relaying full Intents, not just links?
* Investigate background opening without requiring user interaction.

## Screenshots

![IntentBridge main screen](images/intentbridge-ui.png)
