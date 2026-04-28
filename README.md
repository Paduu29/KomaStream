<p align="center">
  <img src="docs/readme/logo.png" width="104" alt="KomaStream logo" align="middle">
  <b style="font-size:32px; vertical-align: middle;">KomaStream</b>
</p>

<p align="center">
  <img src="docs/readme/banner.png" alt="KomaStream banner">
</p>

<p align="center">
  Android manga reader client focused on a clean native UI, local-first tracking, and user-controlled provider access.
</p>

<p align="center">
  <a href="https://github.com/Paduu29/KomaStream/releases">Download latest release</a>
  ·
  <a href="#features">Features</a>
  ·
  <a href="#building-from-source">Build from source</a>
  ·
  <a href="#legal-and-usage">Legal and usage</a>
</p>

KomaStream is an Android app for browsing catalogs, tracking progress, and reading chapters from third-party services directly on your device. The project is built as a client application only: it does not host, upload, mirror, or distribute manga content.

> The screenshots below use sanitized or low-risk examples to avoid exposing unnecessary provider details or third-party artwork in the repository.

## Screenshots

| Home | Catalog | Provider picker |
| --- | --- | --- |
| ![Sanitized home screen](docs/readme/home-sanitized.png) | ![Catalog search screen](docs/readme/catalog-search.png) | ![Provider picker screen](docs/readme/provider-picker.png) |
| Detail | Chapters | Reader |
| ![Sanitized detail screen](docs/readme/details-sanitized.png) | ![Chapter list screen](docs/readme/chapters-list.png) | ![Sanitized reader screen](docs/readme/reader-sanitized.png) |
| Library empty | Library active | Favorites empty |
| ![Empty library screen](docs/readme/library-empty.png) | ![Active library screen](docs/readme/library-active.png) | ![Empty favorites screen](docs/readme/favorites-empty.png) |
| Favorites active | Settings |  |
| ![Active favorites screen](docs/readme/favorites-active.png) | ![Settings screen](docs/readme/settings.png) |  |

## Features

- Native Android app written in Kotlin
- Jetpack Compose UI with light and dark themes
- Browse provider catalogs with search and filtering
- Track favorites, library entries, history, and chapter progress locally
- Backup export and import for local data
- English and Spanish localization
- In-app updater for GitHub release builds
- No account system and no project-operated backend

## How It Works

KomaStream acts as a user-controlled client.

- Content requests are made from the user device to the selected third-party service
- The app does not run content servers or proxy manga files through project infrastructure
- The repository does not ship copyrighted manga content
- User data such as favorites, reading history, progress, and preferences stays on-device

## Tech Stack

- Kotlin
- Android SDK
- Jetpack Compose
- OkHttp
- Jsoup
- WorkManager

## Requirements

### App runtime

- Android 7.0 or newer (`minSdk 24`)

### Development

- Android Studio
- JDK 17
- Android SDK installed locally

## Building From Source

### Clone

```bash
git clone https://github.com/Paduu29/KomaStream.git
cd KomaStream
```

### Build a debug APK

```bash
./gradlew :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Open in Android Studio

1. Open Android Studio.
2. Select **Open**.
3. Choose the `KomaStream` project directory.
4. Let Gradle sync finish.
5. Run the app on an emulator or a physical device.

## Contributing

Contributions are welcome if they stay within the project scope and legal constraints.

- Keep changes focused and well-scoped
- Follow the existing code style and architecture
- Include screenshots for UI changes where useful
- Test changes before opening a pull request
- Do not add or promote unauthorized or infringing content sources

## Privacy Policy

KomaStream does not collect, store, or share any personal user data.

### Data Collection
- The app does not require account registration.
- No personal information (such as name, email, or location) is collected.
- Reading progress and favorites are stored locally on the user’s device.

### Third-Party Services
KomaStream aggregates publicly available manga content from third-party sources. The app does not host any content itself.

Users should be aware that third-party sources may have their own privacy policies and terms.

### Data Usage
All data used by the app is strictly for providing core functionality such as:
- Tracking reading progress
- Managing favorites
- Improving navigation experience

### Security
Since no personal data is collected or transmitted, there is minimal risk related to user privacy.

### Changes
This privacy policy may be updated in the future. Any changes will be reflected in this document.

## Terms of Use

By using KomaStream, you agree to the following terms:

### Usage
KomaStream is provided for personal, non-commercial use only.

### Content
- KomaStream does not host any manga content.
- All content is sourced from third-party providers.
- Users are responsible for ensuring that accessing such content is legal in their region.

### Responsibility
- The developer is not responsible for the content provided by third-party sources.
- The app is provided "as is" without warranties of any kind.

### Restrictions
Users agree not to:
- Use the app for unlawful purposes

### Availability
The app may be updated, modified, or discontinued at any time without notice.

### Changes to Terms
These terms may be updated at any time. Continued use of the app implies acceptance of the updated terms.

## License

This project is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).
