# MicroWifi

Prototipo que permite usar un Android como micrófono en un PC Windows 10 conectado a la misma red de confianza. El audio y el vídeo opcional siguen rutas diferentes.

## Arquitectura y flujos

- **Teléfono:** [`MainActivity.kt`](android/app/src/main/kotlin/com/microwifi/android/MainActivity.kt) configura destino y permisos; [`MicService.kt`](android/app/src/main/kotlin/com/microwifi/android/MicService.kt) mantiene la captura en primer plano. [`AudioCapture.kt`](android/app/src/main/kotlin/com/microwifi/android/AudioCapture.kt) obtiene el audio; [`VoiceIsolation.kt`](android/app/src/main/kotlin/com/microwifi/android/VoiceIsolation.kt) conecta el filtro RNNoise opcional y [`WireProtocol.kt`](android/app/src/main/kotlin/com/microwifi/android/WireProtocol.kt) empaqueta muestras PCM.
- **PC:** [`windows/main.cpp`](windows/main.cpp) recibe datagramas UDP en el puerto 47831 y reproduce el audio en el dispositivo seleccionado; [`stream.hpp`](windows/stream.hpp) implementa el manejo del flujo. Con VB-CABLE, la salida del receptor entra en `CABLE Input` y las aplicaciones de llamadas toman `CABLE Output` como micrófono.
- **Vídeo opcional:** [`MjpegServer.kt`](android/app/src/main/kotlin/com/microwifi/android/MjpegServer.kt) sirve un flujo separado al PC. El README describe abrirlo en el navegador o usar OBS como cámara virtual. No pasa por el receptor UDP de voz.

El flujo no contempla un repositorio de grabaciones. El audio no va cifrado ni autenticado: no lo expongas en redes públicas ni redirijas el puerto del router.

## Arranque y lectura

[Conexión inicial](README.md) → [compilación Android](android/README.md) → [compilación del receptor](windows/README.md). Android documenta API 26+, JDK, Android SDK/NDK y Gradle Wrapper; Windows documenta C++17 y Windows SDK. Los requisitos de red, permisos y dispositivo virtual se comprueban con hardware real, no solo con los archivos del repositorio.

Para entender el flujo, lee `MicService.kt` → `AudioCapture.kt` → `WireProtocol.kt` → `stream.hpp` → `windows/main.cpp`. [`tono-local.ps1`](pruebas/tono-local.ps1) sirve para aislar el tramo del receptor al cable virtual.
