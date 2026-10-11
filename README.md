# ReadAhead

Turns an EPUB into a narrated audiobook on your Android phone, using the text-to-speech engine you choose. Each chapter becomes an `.m4a`; the chapters are then joined into one `.m4b` with chapter markers and tags. It starts at the first real chapter (opening with the title and author, keeping a prologue) and skips the contents, copyright and similar pages. No network access, no account, no player.

Build: `./gradlew assembleDebug` (JDK 25, Android SDK 37).
Check: `./gradlew ktlintCheck detekt lint testDebugUnitTest assembleDebug`.
License: AGPL-3.0 (`LICENSE`).
