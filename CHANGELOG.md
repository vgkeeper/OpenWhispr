# Changelog

Short, human "what's new" notes per release -- the same text shown in the
in-app update dialog when a new version is detected. Add a new `## X.Y.Z`
section at the top before bumping `versionName` in `app/build.gradle.kts`.

Entries here are user-facing only: fixes, bugs, improvements, and features.
No internal/process notes (CI changes, release cleanup, repo housekeeping,
etc.) -- if it wouldn't mean anything to someone who just installed the app,
it doesn't belong here.

## 3.13.0
- Dictionary entries now automatically correct high-confidence recognition variations during dictation.
- Dictionary correction works with or without text cleanup and preserves the spelling you add.

## 3.12.0
- Custom dictionary entries support recognition aliases for names, acronyms, and specialized terms.
- Dictionary spelling guidance now applies during transcription and cleanup, including when custom instructions are set.
- Fixed hotword support for compatible local transducer models.

## 3.11.0
- Added clear attribution and separate source links for the VGKeeper-maintained Android fork and the original OpenWhispr project
- Updated the in-app update check and APK downloads to use this fork's GitHub releases
- Simplified text enhancement setup with Groq recommended and custom service options

## 3.10.0
- Refreshed the app's look with Material 3 Expressive: a richer color palette and more expressive buttons, switches, and tabs
- Smoother, springier animations across the settings screen

## 3.9.0
- Help walkthrough for Android 13+'s "Restricted settings" block, shown before opening Accessibility settings if it's not enabled yet
- The update dialog now shows what's new in the new version, not just the version number
