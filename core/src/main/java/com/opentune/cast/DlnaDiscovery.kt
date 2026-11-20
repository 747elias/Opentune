package com.opentune.cast

import android.os.SystemClock
import com.opentune.data.Http
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** DLNA renderers announce their device descriptions over SSDP. */
internal object DlnaDiscovery {
    suspend fun find(timeoutMs: Long, onDevice: (CastTarget) -> Unit) = withContext(Dispatchers.IO) {
        val seen = HashSet<String>()
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = 500
            val search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n").toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            val buffer = ByteArray(4096)
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var sent = 0
            while (SystemClock.elapsedRealtime() < deadline) {
                ensureActive()
                if (sent < 3) {
                    socket.send(DatagramPacket(search, search.size, group, 1900))
                    sent++
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val location = DlnaDescription.location(String(packet.data, 0, packet.length)) ?: continue
                if (seen.add(location)) launch { describe(location)?.let(onDevice) }
            }
        }
    }

    private fun describe(location: String): CastTarget? = runCatching {
        val uri = URI(location)
        val host = uri.host ?: return null
        val call = Http.client.newCall(Request.Builder().url(location).build())
        call.timeout().timeout(3, TimeUnit.SECONDS)
        val xml = call.execute().use { response -> response.body?.takeIf { response.isSuccessful }?.string().orEmpty() }
        val device = DlnaDescription.parse(xml, location) ?: return null
        CastTarget(device.id, device.name, CastTarget.Kind.DLNA, host, dlna = device)
    }.getOrNull()
}
