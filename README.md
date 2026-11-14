# Reborn IPTV for Fire TV

Fire TV / Android TV app for Reborn IPTV. It opens https://reborn-iptv.vercel.app full-screen and adds:

- Fire TV home-screen banner and icon
- D-pad navigation with a red focus ring (no mouse needed)
- OK button clicks, Back closes the player, then goes back, then exits (press twice)
- Play/Pause, Rewind and Fast-forward remote buttons control the video
- Full-screen video, screen stays on while watching
- "Can't reach Reborn" screen with a retry button when offline

Because it loads the live site, anything you deploy to Vercel shows up on the Fire TV straight away.

## Getting the APK

The GitHub Action in `.github/workflows/build-apk.yml` builds the APK every time you push to `main`,
and publishes it as a release called **latest** with the file `RebornIPTV.apk`.
You can also run it by hand: Actions tab → Build Fire TV APK → Run workflow.

**The repo must be public for the Fire TV to download the APK without signing in.**
If you keep it private, download the APK on your phone and send it to the Fire TV another way
(e.g. "Send Files to TV" app).

## Installing on the Fire TV

1. Settings → My Fire TV → Developer options → Install unknown apps → turn on **Downloader**.
   (No Developer options? Settings → My Fire TV → About → click the device name 7 times.)
2. Open Downloader and enter:
   `https://github.com/<your-username>/<repo>/releases/download/latest/RebornIPTV.apk`
3. Install, then open **Reborn IPTV** from Your Apps.

## Changing the address

Edit `START_URL` in `app/build.gradle` and push. The new APK will build automatically.
