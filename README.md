# BookJam

An audiobook player for one phone. Point it at a folder of audio files and it
becomes a book: named after the folder, with the folder's cover, its files
played in number order, and your place kept to the second — through
accidental skips, sudden closes and the phone running out of battery.

Built as a plain Android app in Java with **no libraries at all**: no AndroidX,
no Kotlin runtime, no media library. Playback is the framework's own
`MediaPlayer` and `MediaSession`, so the whole thing is a few hundred
kilobytes. The APK is produced by GitHub Actions; there is nothing to install
locally.

## Using it

**Add a folder.** Tap *Add a folder* (or **+**) and pick a folder in the system
picker. A folder with audio files in it is one book. A folder of folders — say
`Audiobooks/` with a sub-folder per book — adds every book inside it, and
BookJam looks in there again on each launch, so books you copy in later just
appear. Sub-folders named `CD 1`, `Disc 2` or `Part 3` are taken as one book
split up. No storage permission is involved: the picker grants access to the
folders you choose and nothing else.

**The order.** Files play in natural order, the way you would read them:
`1, 2, 3 … 9, 10, 11`, not `1, 10, 11, 2`. Leading zeros and capitals make no
difference.

**The library** is the home screen: every book with its cover, how far in you
are and the time left, the one you listened to last on top. The bar at the
bottom resumes that book exactly where it stopped. Touch and hold a book to
start it over or remove it (removing keeps your place, in case you add it
again).

**The player** keeps the cover on top and everything you touch in the bottom
half, in reach of a thumb:

- the file's title, the book, and how much of the book is left at your speed;
- a slider for the current file with time gone and time left;
- three big buttons: **back 10 s**, **play/pause**, **forward 10 s**. Every
  tap is another 10 seconds, so a double tap is 20 and a triple tap 30, and
  the total flashes over the cover. Running past the start or end of a file
  carries on into the previous or next one;
- **speed** from 0.5× to 3× in 0.05 steps, with presets, kept per book; voices
  keep their pitch;
- a **sleep timer**: 5 to 90 minutes, any number of minutes up to three hours,
  or the end of the current file. The last 15 seconds fade out, then
  playback stops, your place is saved, and BookJam closes itself;
- **chapters**: every file with its length and how far you got in it.

**Never losing your place.** Your position is written to a small database every
3 seconds while playing and on every pause, seek and file change, so even a
force-close or a dead battery costs at most a few seconds. Each file also
remembers its own position: skip out of a chapter by mistake, go back to it,
and it picks up where you were. Jumping to another chapter or dragging the
slider a long way shows an **Undo** pill for 12 seconds that takes you straight
back.

**In the background.** Playback carries on with the app closed or the screen
locked, with the lock screen, quick panel, watch and car showing back 10,
play/pause and forward 10. Headphone next/previous move 10 seconds rather than
a whole file, so a stray double tap on an earbud never loses your chapter.
Unplugging headphones or a Bluetooth drop pauses; a phone call or navigation
prompt pauses and resumes after.

**Looks.** Follows the phone's dark mode. One UI's ideas throughout: a big
title over the top third of the library that folds away as you scroll, large
rounded corners, floating sheets at the bottom, and the player's background
tinted from the cover.

Target device is a Galaxy S24 Ultra, so `minSdk` is 34 and there is no
compatibility code for anything older.

## Getting the APK

Every push builds one. Open the **Actions** tab, pick the latest *Build APK*
run and download the `bookjam-apk` artifact (a zip with the APK in it).
Pushing a tag like `v1.0` also publishes a GitHub release with the APK
attached.

By default CI signs with a throwaway key generated for that run, which means
consecutive builds have different signatures — uninstall the old copy before
installing a new one (your library goes with it). To get a stable signature,
so updates install over the top and keep your books and places, add four
repository secrets: `KEYSTORE_BASE64` (`base64 -w0 your.jks`), `KEY_ALIAS`,
`KEY_PASSWORD` and `STORE_PASSWORD`.

## What CI does

1. **Test and build release APK** — runs the JVM unit tests (natural ordering,
   time and speed formats), assembles and signs the APK, prints a size
   breakdown in the job summary and fails past a 1.5 MB budget.
2. **Listen to a book on an emulator** — `tools/make_test_books.py` writes two
   small audiobooks of WAV files (`Chapter 1`, `Chapter 2`, `Chapter 10` and a
   cover; and a short two-file book). `tools/smoke_test.sh` installs the APK on
   Android 14, adds the folder through the real system picker, and then checks,
   through `dumpsys media_session` — what the lock screen sees:
   - both books appear, named after their folders;
   - play starts and a notification is up;
   - three taps forward move exactly 30 s and one tap back 10 s, and 70 s on
     from near the end of a file lands in the next one at the right second;
   - the chapter sheet lists `Chapter 10` third, a jump plays it, and Undo
     returns to the same second of the previous file;
   - speed 1.5× is applied and the sleep timer sets and cancels;
   - it keeps playing on the home screen and with the screen off;
   - after `kill -9` it comes back in the same file within seconds of where it
     was;
   - "end of this chapter" stops at the end of the file, removes the
     notification, closes the app, and next launch starts at the next file.

   It fails on any crash, and uploads screenshots of every step.

## Layout

```
app/src/main/java/com/bookjam/
  MainActivity.java     the library, the folder picker, the resume bar
  PlayerActivity.java   the player screen and the multi-tap skip buttons
  Sheets.java           speed, sleep timer, chapters and book-options sheets
  Player.java           MediaPlayer, MediaSession, audio focus, saving, sleep timer, undo
  PlaybackService.java  foreground service and the playback notification
  Library.java          SQLite: books, files, positions
  Scanner.java          reads folders through the storage access framework
  Covers.java           finds, scales and caches covers; initials when there is none
  Natural.java          "2" before "10" ordering
  Slider.java           the thick One UI slider
  Ui.java  Fmt.java     palette and view helpers; time and speed text
app/src/test/java/com/bookjam/
  NaturalTest.java  FmtTest.java
tools/
  make_test_books.py    writes the audiobooks the emulator test plays
  smoke_test.sh         drives the APK on an emulator
```

## Building locally

Needs JDK 17 and an Android SDK with platform 35:

```bash
./gradlew testReleaseUnitTest
./gradlew assembleRelease     # add -PbjStoreFile=... to sign it
```
