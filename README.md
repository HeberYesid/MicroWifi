# MicroWifi

Micrófono de Android para Windows 10 por Wi‑Fi local. El teléfono envía voz al PC y el receptor la entrega a un dispositivo de audio virtual. El PC puede estar conectado al router por Ethernet.

## Primera conexión

Si aún no tienes las aplicaciones, consulta cómo compilar el [receptor de Windows](windows/README.md) y el [APK de Android](android/README.md). Instala el APK en el teléfono antes de iniciar la conexión.

1. Instala [VB-CABLE desde su sitio oficial](https://vb-audio.com/Cable/) en Windows. Reinicia si el instalador lo solicita.
2. Abre el receptor de MicroWifi y selecciona **CABLE Input** como destino del sonido. Anota la IPv4 del adaptador conectado al router. Si Windows pregunta por el firewall, permite el receptor en tu **red privada**.
3. Conecta Android a la misma red, abre la app e introduce esa IPv4. Concede el permiso de micrófono y empieza a transmitir. Para terminar, detén el envío desde la app o su notificación.
4. En Windows abre **Configuración → Sistema → Sonido → Entrada** y selecciona **CABLE Output**. Habla y comprueba que se mueve el medidor. En la aplicación de llamadas selecciona también **CABLE Output** como micrófono. Mantén tus auriculares o altavoces habituales como salida de la llamada.

Si no llega audio, comprueba la IP, el destino del receptor y que el firewall permita UDP entrante en el puerto **47831**. Una red de invitados puede impedir que el móvil y el PC se comuniquen. Si el medidor de Windows responde pero tu llamada sigue en silencio, revisa el micrófono elegido en esa aplicación y sus permisos de micrófono en Windows.

Para aislar un problema, detén el envío desde Android y ejecuta `pruebas\tono-local.ps1` en PowerShell en el PC, con el receptor activo. Envía dos segundos de tono directamente al receptor local. Si se mueve el medidor de **CABLE Output**, el tramo receptor → cable virtual funciona y puedes centrarte en el móvil o la red.

## Alcance del prototipo

El audio viaja sin cifrado ni autenticación: utiliza una red privada de confianza y no redirijas el puerto en el router. Cada paquete representa 10 ms: `MWF1`, un contador de 32 bits en orden de red y 480 muestras mono PCM de 16 bits little-endian a 48 kHz. La voz consume 0,768 Mbps antes de cabeceras. Esta cifra no mide la RAM ni la latencia.

## Prueba de consumo y estabilidad

Con el medidor de entrada funcionando, inicia una llamada de 30 minutos y apaga la pantalla del móvil. En el Administrador de tareas anota memoria y CPU del **receptor**, primero en reposo y después en los minutos 1, 15 y 30. Registra también cortes audibles y batería inicial/final del móvil. Al detener la transmisión, comprueba que el medidor deja de responder y desaparece el indicador de uso del micrófono en Android. Estas mediciones requieren equipos reales.

## Cómo comparar el filtro y probar el vídeo

- **Ruido:** deja el teléfono en el mismo lugar y graba dos veces la misma frase en el PC, primero sin filtro y después con él. Deja unos segundos de silencio con el ventilador encendido y pulsa varias teclas, tanto en silencio como mientras hablas. Compara al mismo volumen y sin el filtro de Discord o Zoom para escuchar solo el efecto del teléfono. Un ruido continuo suele ser más fácil de atenuar que una tecla pulsada mientras hablas.
- **Vídeo:** desde el PC configurado en Android, abre la dirección de vídeo indicada por la app. Si la imagen se ve en el navegador, la transmisión hasta el PC funciona. Para que una aplicación de llamadas la reconozca como cámara en Windows 10, usa esa dirección como fuente multimedia de red en OBS y activa su cámara virtual. Selecciona **OBS Virtual Camera** para el vídeo y **CABLE Output** para la voz.
- **Consumo:** compara solo micrófono frente a micrófono + cámara por separado. Si utilizas el navegador u OBS para el vídeo, incluye ese proceso en la medición; la memoria del receptor por sí sola no representa el coste del vídeo.

## Integración continua

El flujo [CI de GitHub Actions](https://github.com/HeberYesid/MicroWifi/actions/workflows/ci.yml) se ejecuta en los envíos y solicitudes de cambio a `main`, y también se puede iniciar manualmente. Compila el APK de depuración, ejecuta las pruebas unitarias y lint de Android, y compila el receptor x64 y ejecuta la prueba del protocolo en Windows.

Tras un envío a `main`, la ejecución deja disponibles durante 14 días el APK de depuración y el ejecutable de Windows, si pasan las comprobaciones respectivas. Son artefactos de prueba, no versiones publicadas; el APK usa la clave de depuración del entorno de compilación. La transmisión con un teléfono y la salida por VB-CABLE se comprueban aparte con equipos reales.
