# YT Ad Skip

Android app using an AccessibilityService to auto-tap YouTube's "Skip Ad" button.

## Build via GitHub Actions

1. Create a new GitHub repo, push this entire folder's contents to it (`main` branch).
2. Go to the repo's **Actions** tab. The `Build Debug APK` workflow runs automatically on push
   (or trigger manually via "Run workflow").
3. When it finishes, open the workflow run → **Artifacts** → download `app-debug-apk`.
4. Unzip it, install the `.apk` on your device (`adb install app-debug.apk`, or copy to
   the device and open it — enable "install unknown apps" for whichever app you use to open it).
5. Open the app → tap **Enable Service** → turn it on under Accessibility settings.

## Updating when YouTube changes its UI

Everything you'll ever need to touch lives in one file:

`app/src/main/java/com/example/adskip/YoutubeAdSkipService.kt`

- If the skip button stops being detected, add new wording to `SKIP_TEXT_PATTERNS`
  (e.g. YouTube changes "Skip Ad" → "Skip Ads in 5s" style text).
- Edit that file directly on GitHub (pencil icon) or push a new commit — either way, the
  Actions workflow rebuilds automatically and a new APK artifact is ready in a couple minutes.
- No need to touch anything else (UI, manifest, gradle config) for ad-skip logic changes.

## Project structure

```
app/src/main/java/com/example/adskip/
  MainActivity.kt              — status screen, opens Accessibility settings
  YoutubeAdSkipService.kt      — the actual skip-detection logic (edit this to update)
app/src/main/res/
  layout/activity_main.xml     — UI layout
  values/strings.xml           — text shown in the UI
  xml/accessibility_service_config.xml — service capability declaration
.github/workflows/build-apk.yml — CI build, produces the APK on every push
```
