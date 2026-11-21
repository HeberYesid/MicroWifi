# MicroWifi para Android

App nativa Kotlin para **Android 8.0 (API 26)** o posterior, con RNNoise para procesar la voz en el teléfono. JUnit se usa solo en las pruebas locales.

## Compilar

Requisitos: JDK 17 (o un JDK compatible con Gradle 9.1), Android SDK Platform 36, Build Tools 36.0.0, NDK 28.2.13676358 y CMake 3.22.1. Configura `JAVA_HOME` y `ANDROID_HOME` o pon `sdk.dir` en `local.properties`. El wrapper fija Gradle 9.1.0 y el SHA-256 de su distribución; AGP está fijado en 9.0.1 y aporta el compilador Kotlin.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

En Linux/macOS, ejecuta `sh ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. APK: `app/build/outputs/apk/debug/app-debug.apk`.

El APK de depuración se firma con una clave local; el wrapper fija las herramientas, pero ese APK firmado no es idéntico entre equipos con claves distintas.

## Uso y prueba en un teléfono

1. Conecta el teléfono y el PC al mismo router. Con el receptor escuchando en UDP 47831, introduce la IPv4 del PC y toca **Iniciar transmisión**. La app guarda la última dirección válida que se usó para iniciar.
2. Concede permiso de micrófono. En Android 13 o posterior permite también las notificaciones para disponer de **Detener** en la notificación. Si las rechazas, puedes detener desde la app.
3. Confirma que llega audio; cambia de app y bloquea la pantalla. La captura solo debe continuar mientras el servicio en primer plano esté activo.
4. Detén desde la notificación y luego repite desde la app. Confirma que cesan los datagramas y desaparece el indicador de micrófono. Desactiva el Wi‑Fi durante otra transmisión para comprobar que se detiene en lugar de pasar a datos móviles.

El envío es UDP sin cifrado, autenticación ni confirmación de recepción. Úsalo solo en una red privada de confianza. Cada datagrama tiene 968 bytes: `MWF1` (4), secuencia uint32 big-endian (4, empieza en cero por transmisión) y 480 muestras PCM16 little-endian mono a 48 kHz (960 bytes, 10 ms).

### Vídeo opcional en el navegador u OBS

1. En el teléfono, antes de iniciar, activa **Compartir vídeo de la cámara** y concede el permiso de cámara cuando Android lo pida. Está desactivado al abrir la app por primera vez. Si deniegas el permiso o falla el vídeo, el audio puede seguir por separado.
2. Copia la URL que muestra el teléfono, por ejemplo `http://192.168.1.55:47832/video`, y ábrela directamente en el navegador del PC. En OBS, añade una **Fuente multimedia**, desmarca **Archivo local** y pega esa URL en **Entrada**. El vídeo no lleva audio; añade por separado la entrada de audio del receptor. Si necesitas girar la imagen, usa **Transformar** en OBS.
3. El teléfono y el PC deben compartir la misma subred IPv4. La app escucha en la IPv4 del Wi‑Fi, no en todas las interfaces, y solo admite la IPv4 del PC configurada (un cliente a la vez). Para pasar del navegador a OBS, cierra antes el navegador. Si la IPv4 del teléfono cambia, reinicia la transmisión.
4. Detén la transmisión y comprueba que la URL deja de responder y que Android deja de mostrar el uso de la cámara. Haz la misma prueba desconectando el Wi‑Fi. Con la opción desactivada, comprueba que la app no pide permiso, no usa la cámara y no abre el puerto TCP. Desde otro equipo, la URL no debe devolver vídeo aunque esté en la misma red.

El vídeo usa MJPEG por HTTP. Se prioriza la cámara trasera, con captura continua y objetivo de 720p a 24–30 fps. La resolución y cadencia efectivas dependen de los modos disponibles, la luz, el tiempo de codificación y la red. No hay cifrado, contraseña ni autenticación de identidad: la restricción por IP no los sustituye. Úsalo únicamente en una red privada de confianza y no redirijas el puerto 47832 en el router.

### Reducción de ruido

Activa o desactiva **Aislar voz del ruido** durante la sesión. RNNoise procesa la mezcla de voz y ruido en el teléfono sin cambiar el formato UDP. Si el filtro falla, el audio sigue sin él. No distingue tu voz de otras voces. Para probarlo, compara la misma frase tecleando junto al ventilador, con el filtro activado y desactivado.
