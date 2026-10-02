# thehomeodoc Android app

This directory is a fully native Android client for the existing thehomeodoc backend.

## Architecture

- Kotlin + Jetpack Compose
- No WebView
- Existing Next.js/Prisma/Meta backend remains the source of truth
- Native API calls authenticate with a dedicated bearer session
- Email magic-link verification opens in the system browser and returns to the app through `thehomeodoc://auth`
- Session token is stored with Android Keystore-backed encrypted preferences
- Periodic WorkManager checks can surface new DM failures as Android notifications

## Build

From the repository root:

```bash
gradle -p android assembleDebug
```

The GitHub Actions workflow `.github/workflows/android-build.yml` also builds and uploads the debug APK.

## Production backend

The current production API is configured in `android/app/build.gradle.kts` as:

```
https://openreply-u6me.onrender.com
```

No Meta access token, database password, Redis credential, or other server secret is bundled into the APK.
