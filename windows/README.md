# MicroWifi para Windows 10

Receptor nativo C++17 x64 para Windows 10. No usa instalador ni servicios.

## Compilar

En una **x64 Native Tools Command Prompt** de Visual Studio o Build Tools con la carga de C++ de escritorio y Windows SDK, desde este directorio:

```bat
cl /nologo /std:c++17 /EHsc /utf-8 /W4 /MT /DUNICODE /D_UNICODE /D_WIN32_WINNT=0x0A00 main.cpp /Fe:MicroWifi-video.exe /link /SUBSYSTEM:WINDOWS ws2_32.lib iphlpapi.lib shell32.lib user32.lib ole32.lib uuid.lib
```

Alternativa con **LLVM-MinGW UCRT x86-64**, desde el mismo directorio:

```bat
g++ -std=c++17 -O2 -Wall -Wextra -static -municode -mwindows -D_WIN32_WINNT=0x0A00 main.cpp -o MicroWifi-video.exe -lws2_32 -liphlpapi -lshell32 -lole32 -luuid
```

## Conectar

1. Instala [VB-CABLE](https://vb-audio.com/Cable/) y abre `MicroWifi-video.exe`. Si usas `MicroWifi.exe`, sal primero desde su icono de bandeja cuando puedas interrumpir el audio: ambos requieren el mismo puerto UDP y no pueden funcionar al mismo tiempo.
2. Abre el icono de **MicroWifi** en la bandeja (puede estar bajo `^`). En **Dispositivo de reproducción**, selecciona **CABLE Input (VB-Audio Virtual Cable)** y confirma. Para que el audio quede en el cable virtual, no elijas altavoces ni auriculares.
3. En **Ver IPv4 (UDP 47831)**, toma la IPv4 del Wi-Fi o Ethernet que comparte router con el móvil. Si Windows solicita acceso al firewall, limítalo a redes privadas de confianza.
4. En Windows o tu aplicación de llamadas, elige **CABLE Output** como micrófono. Si quieres aislar la prueba del móvil, usa `..\pruebas\tono-local.ps1` y observa el medidor de entrada de Windows; no necesitas escuchar el tono en altavoces.

El receptor arranca con la salida desactivada y no guarda la elección al salir. Abre solo el dispositivo explícitamente elegido, incluso si el dispositivo predeterminado cambia. Si se produce un error al abrirlo o reproducir, silencia la salida y pide seleccionarlo de nuevo; nunca usa la salida predeterminada como alternativa. Usa **Desactivar salida** para silenciar o **Salir** para cerrar también el puerto.

## Ver la cámara del teléfono

1. En Android, inicia el envío de audio hacia el PC y activa la cámara. Mantén el audio transmitiendo mientras eliges la opción de vídeo; en Windows la salida de audio puede estar desactivada.
2. En la bandeja, elige **Abrir vídeo del teléfono en el navegador** o **Copiar enlace de vídeo**. El enlace es `http://<IPv4-del-emisor>:47832/video` (MJPEG por HTTP). MicroWifi no abre el navegador ni hace una solicitud HTTP por su cuenta.
3. Si aparece «No hay un teléfono activo», revisa que llegue audio UDP desde el móvil. Si obtienes el enlace pero no hay imagen, confirma que la cámara esté activada y que el PC pueda alcanzar el puerto TCP `47832` del móvil. El enlace identifica al último emisor de audio aceptado (300 ms de vigencia); por sí solo no confirma que la cámara esté activa.

Usa una red privada de confianza: se comprueba el formato del audio, pero no se autentica el teléfono ni se cifra el vídeo. No abras esos puertos a Internet.

### Opcional: usarlo como webcam en Windows 10 con OBS

1. Abre el vídeo en una ventana dedicada del navegador. En OBS Studio, crea una escena y añade una [**Captura de ventana**](https://obsproject.com/kb/window-capture-sources) para esa ventana. Ajusta el encuadre para que solo se vea el vídeo y no la barra del navegador.
2. En **Controles**, pulsa [**Iniciar cámara virtual**](https://obsproject.com/kb/virtual-camera-guide). En tu aplicación de llamadas selecciona **OBS Virtual Camera** como cámara y **CABLE Output** como micrófono. La cámara virtual solo lleva el vídeo.
3. Al terminar, detén la cámara virtual en OBS y la transmisión en Android. OBS es opcional; no es una dependencia del receptor.

## Protocolo y pruebas

Escucha IPv4 UDP `47831`. Acepta exactamente 968 bytes: `MWF1` en ASCII, secuencia `uint32` big-endian y 480 muestras mono PCM16 little-endian a 48 kHz (10 ms). Un solo emisor (IP y puerto) a la vez. No hay cifrado ni autenticación: no expongas el puerto a Internet.

El búfer de reordenación tiene 8 posiciones (80 ms de audio, no equivale a la latencia total). Arranca con tres paquetes o tras esperar hasta 40 ms; descarta duplicados y paquetes atrasados, rellena pérdidas con silencio y vuelve a acumular tras un salto grande o al quedarse sin audio. Tras 300 ms sin paquetes aceptados, vacía la cola de audio y admite un nuevo emisor o una secuencia reiniciada.

Para probar la decodificación, secuencias cíclicas, reordenación, duplicados, pérdidas, reinicio, límites del búfer y vigencia del emisor de vídeo sin un dispositivo de audio:

```bat
cl /nologo /std:c++17 /EHsc /utf-8 /W4 stream_test.cpp /Fe:stream_test.exe
stream_test.exe
```

Con LLVM-MinGW: `g++ -std=c++17 -Wall -Wextra -static stream_test.cpp -o stream_test.exe`.
