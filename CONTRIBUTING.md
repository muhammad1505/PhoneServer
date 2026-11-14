# Linters and check tasks

## ktlint

```bash
./gradlew ktlintCheck
./gradlew ktlintFormat        # manual formatting (not committed by default)
```

`ktlintCheck` is check-only and part of CI.

## detekt

```bash
./gradlew detekt              # fails on findings, writes the report
```

## lint

```bash
./gradlew :app:lint            # report only, does not modify files
./gradlew :app:lintFix         # applies safe automatic fixes
```

Lint has `abortOnError = true` in the app module so a failing check stops the build.

## Other Gradle tasks

```bash
./gradlew :core:test                        # core unit tests
./gradlew :server:test                      # Ktor integration tests
./gradlew :tunnel:test                      # tunnel supervisor tests
./gradlew :data:compileDebugKotlin          # Room + Hilt compile
./gradlew :app:assembleDebug                # debug APK
./gradlew :app:assembleRelease              # release APK (needs keystore env vars)
./gradlew :app:assembleDebugAndroidTest     # Android test APK (run on physical device)
```

## Version catalog

Pinned versions live in `gradle/libs.versions.toml` and are reviewed at every release.
