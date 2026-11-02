# Third-party notices

## ZZUPocket

This project was researched using https://github.com/3beuml/ZZUPocket (MIT, copyright 2026 3beuml).
The local reference checkout is excluded from this repository. Gradle wrapper files were copied from that checkout.
The application source in `app/src/main/java/cn/nuist/pocket` was written for this project, based on the observed NUIST response contracts rather than the Zhengzhou University protocol implementation.

The upstream MIT license is retained in `third_party/ZZUPocket-LICENSE`.

## Gradle wrapper

The `gradlew`, `gradlew.bat` and `gradle-wrapper.jar` files are Gradle wrapper components, under Apache License 2.0.
The script license headers are preserved. Gradle's license and notice are retained in `third_party/Gradle-LICENSE` and `third_party/Gradle-NOTICE`. See https://github.com/gradle/gradle/blob/master/LICENSE.

## Runtime dependencies

- [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded) 4.3.0 and [ZXing](https://github.com/zxing/zxing) core: Apache License 2.0; copyright 2012–2022 ZXing authors and Journey Mobile. The license text is retained in `third_party/Gradle-LICENSE`.

AndroidX / Jetpack Compose: Apache License 2.0, https://android.googlesource.com/platform/frameworks/support/.

Kotlin / kotlinx.serialization / kotlinx.coroutines: Apache License 2.0, https://github.com/JetBrains/kotlin and https://github.com/Kotlin.

Temurin JDK is a development-only tool under its included licenses; it is not packaged in the APK.

## Example data

Course and exam assets are fictional demonstration records, not the user's actual records. The classroom example is a non-personal room-occupancy response provided by the user. Period times are from the user's school response. The application is an unofficial student project and its icon is newly drawn, not the university emblem.
