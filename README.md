# KomaStream

KomaStream is an Android manga reader client focused on browsing, tracking, and reading publicly accessible manga metadata and page content from third-party sources.

The app is a client interface. It does not host, upload, store, or redistribute the source catalog or chapter content it displays at runtime.

## Overview

KomaStream provides a native Android interface for:

- browsing recent and popular manga updates
- searching and filtering a remote catalog
- opening manga details and chapter lists
- reading chapters in-app
- saving favorites and reading progress locally
- downloading chapters for offline reading on the user device
- exporting and importing local backups
- checking for app updates through GitHub Releases

## Features

- Native Android app written in Kotlin
- Jetpack Compose UI
- Local favorites, history, and chapter progress
- Offline chapter download support
- Backup export and import
- Light and dark theme support
- English and Spanish localization
- In-app updater for GitHub release builds

## How It Works

KomaStream connects to third-party websites at runtime and requests data that is already publicly available from those services. The application renders that data in a mobile-friendly interface and stores only user-specific local state on the device, such as:

- favorites
- reading history
- reading progress
- downloaded offline files created by the user
- app preferences

KomaStream is not a hosting platform, CDN, relay service, or content repository.

## Legal Notice

KomaStream is a client application and interface layer.

- The project does not host manga files, images, catalogs, or chapter databases on its own servers.
- The project does not upload or publish third-party source material.
- All third-party content is requested by the user’s device directly from external services at runtime.
- Rights to any displayed content remain with their respective owners.

Users are responsible for ensuring that their use of any third-party source complies with applicable law, local regulations, and the terms of the content provider they access.

This repository is provided for software development and educational purposes. Nothing in this repository should be interpreted as legal advice, a license to access protected material, or a claim of ownership over third-party content.

If you are a rights holder and believe specific material or behavior should be reviewed, open an issue or contact the repository owner with the relevant details.

## Privacy

KomaStream does not require an account for core app usage.

The app stores user preferences and reading data locally on the device. Network requests are made directly from the user’s device to external services selected by the app’s implementation.

## Tech Stack

- Kotlin
- Android SDK
- Jetpack Compose
- OkHttp
- Jsoup
- WorkManager

## Building

### Requirements

- Android Studio with JDK 17 support
- Android SDK configured locally

### Debug build

```bash
sh gradlew :app:assembleDebug
```

## Project Status

KomaStream is an actively maintained personal Android project. The codebase may change frequently as source integrations, UI behavior, and release tooling evolve.

## License

This repository is currently distributed under the license included in [LICENSE](LICENSE).
