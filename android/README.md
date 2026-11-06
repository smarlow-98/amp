# AMP for Android

Screen-reader accessible media player for Android 11 and newer.

- Plays local music and videos using Google's Media3 engine
- View and edit ID3 tags on MP3 files (double tap and hold a song, or use Edit tags on Now playing)
- Sign in with any Plex account and play music, movies and TV from your servers
- Background playback with lock screen and notification controls
- Built for TalkBack: real Android controls, headings, spoken durations

## Building

GitHub builds the app automatically whenever anything in the `android` folder changes.
When the build finishes, the APK is on the Releases page under
"AMP for Android (latest build)". Download `AMP-android.apk` from there.
Each new build replaces the old one, so it doesn't use up Actions storage.

Each build is signed with a fresh key, so uninstall the previous AMP before
installing a new build.
