# KomaStream

KomaStream is an Android manga reader client designed for browsing, tracking, and reading manga content from third-party services.

The application functions as a user-controlled interface and does not host, upload, or distribute any copyrighted content.

Copyright (C) 2026 Paduu29

## Overview

KomaStream provides a native Android interface for:

- browsing manga catalogs
- searching and filtering content
- viewing manga details and chapter lists
- reading chapters within the app
- saving favorites and reading progress locally
- exporting and importing local backups

## Features

- Native Android app written in Kotlin  
- Jetpack Compose UI  
- Local favorites, history, and chapter progress  
- Backup export and import  
- Light and dark theme support  
- English and Spanish localization  
- In-app updater for GitHub release builds  

## How It Works

KomaStream acts as a client interface that retrieves data from third-party services at the user's request.

The app itself:

- does not host or store manga content on external servers  
- does not operate as a content provider or distributor  
- does not control or modify third-party content sources  

All requests are made directly from the user’s device to external services.

The application stores only local user data, including:

- favorites  
- reading history  
- reading progress  
- app preferences  

## Important Usage Notice

KomaStream is intended to be used only with content sources that the user has the legal right to access.

The developer of this project:

- does not provide or maintain any content sources  
- does not endorse or promote access to unauthorized or infringing material  
- is not responsible for how third-party services are used within the app  

Users are solely responsible for ensuring that their use of any third-party service complies with:

- applicable copyright laws  
- local regulations  
- the terms of service of the respective content providers  

## Legal Disclaimer

This project is provided as an open-source software client.

- No copyrighted material is included in this repository  
- No content is redistributed by the project itself  
- All trademarks and copyrights belong to their respective owners  

This repository is intended for software development and educational purposes only.

Nothing in this project should be interpreted as:

- legal advice  
- authorization to access protected content  
- endorsement of any specific third-party service  

If you are a rights holder and have concerns regarding this project, please open an issue or contact the repository owner with relevant details.

## Privacy

KomaStream does not require user accounts.

All user data is stored locally on the device.  
The app does not operate backend servers or collect personal data.

Network requests are performed directly between the user’s device and third-party services.

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
Project Status

KomaStream is an actively maintained personal Android project.
Functionality and integrations may change over time.

## License

This project is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).

