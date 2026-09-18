# WiFiFileShare

Modern Android local-network file sharing over Wi-Fi.

## Build stack

- Android Gradle Plugin 9.3.1
- Gradle 9.5.0
- Kotlin 2.4.20
- Jetpack Compose BOM 2026.09.00
- compileSdk / targetSdk 37
- JDK 17

The build uses the latest mutually compatible stable Kotlin/AGP/Gradle combination verified against official compatibility guidance.

## Transfer

The app uses local TCP sockets for file transfer and UDP discovery on the same Wi-Fi network. Files are written through MediaStore to Downloads/WiFiFileShare.

## CI

GitHub Actions builds app-debug.apk and uploads it as the WiFiFileShare-debug artifact.
