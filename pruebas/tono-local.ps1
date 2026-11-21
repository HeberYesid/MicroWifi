# Con el receptor abierto, envía 2 s de tono a este PC, sin usar el móvil ni la LAN.
$ErrorActionPreference = 'Stop'
$udp = [System.Net.Sockets.UdpClient]::new()
try {
    $udp.Connect([System.Net.IPAddress]::Loopback, 47831)
    [byte[]]$packet = New-Object byte[] 968
    [Text.Encoding]::ASCII.GetBytes('MWF1').CopyTo($packet, 0)
    # 500 Hz: exactamente cinco ciclos por paquete. Amplitud baja (~6 %).
    for ($i = 0; $i -lt 480; $i++) {
        $sample = [int16](2000 * [Math]::Sin(2 * [Math]::PI * $i / 96))
        $packet[8 + 2 * $i] = $sample -band 255
        $packet[9 + 2 * $i] = ($sample -shr 8) -band 255
    }
    $clock = [Diagnostics.Stopwatch]::StartNew()
    for ($seq = 0; $seq -lt 200; $seq++) {
        $packet[7] = [byte]$seq
        [void]$udp.Send($packet, $packet.Length)
        while ($clock.ElapsedMilliseconds -lt (($seq + 1) * 10)) {
            [Threading.Thread]::Sleep(1)
        }
    }
} finally {
    $udp.Dispose()
}
