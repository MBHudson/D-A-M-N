package com.damn.app.server

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * NAT Port Mapper via UPnP IGD.
 * Mirrors `php -S` + nat forwarding: discovers router via SSDP M-SEARCH,
 * fetches device description XML, finds WANIPConnection / WANPPPConnection control URL,
 * then AddPortMapping / DeletePortMapping via SOAP.
 */
object NatPortMapper {
    private const val TAG = "DAMN-NAT"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SSDP_MX = 2
    private const val SSDP_TIMEOUT_MS = 4000

    data class Gateway(
        val locationUrl: String,
        val controlUrl: String,
        val serviceType: String,
        val localIp: String
    )

    private var currentMapping: Pair<Gateway, Int>? = null

    fun mapPort(port: Int, description: String = "DAMN PHP Server"): Result<Gateway> {
        return try {
            val gw = discoverGateway() ?: return Result.failure(Exception("No UPnP IGD found. Enable UPnP on router or forward port $port manually."))
            val localIp = getLocalIp()
            val externalIp = getExternalIp(gw)
            val success = addPortMapping(gw, localIp, port, description)
            if (!success) return Result.failure(Exception("AddPortMapping SOAP failed"))
            currentMapping = gw to port
            Log.i(TAG, "Mapped $localIp:$port -> $externalIp:$port via ${gw.locationUrl}")
            Result.success(gw)
        } catch (e: Exception) {
            Log.e(TAG, "mapPort failed", e)
            Result.failure(e)
        }
    }

    fun unmapPort() {
        val (gw, port) = currentMapping ?: return
        try { deletePortMapping(gw, port) } catch (e: Exception) { Log.w(TAG, "unmap failed", e) }
        currentMapping = null
    }

    fun getExternalIp(gw: Gateway): String? = try {
        val body = soapBody(gw.serviceType, "GetExternalIPAddress", "")
        val resp = soapRequest(gw.controlUrl, gw.serviceType, "GetExternalIPAddress", body)
        Regex("<NewExternalIPAddress>(.*?)</NewExternalIPAddress>").find(resp)?.groupValues?.get(1)
    } catch (_: Exception) { null }

    // ---- discovery ----

    private fun discoverGateway(): Gateway? {
        val stOptions = listOf(
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1"
        )
        val locations = mutableSetOf<String>()
        for (st in stOptions) {
            locations.addAll(ssdpSearch(st))
            if (locations.isNotEmpty()) break
        }
        if (locations.isEmpty()) {
            Log.w(TAG, "SSDP found no locations")
            return null
        }
        for (loc in locations) {
            try {
                val gw = parseDeviceDescription(loc) ?: continue
                Log.i(TAG, "Found gateway $loc -> ${gw.controlUrl} type=${gw.serviceType}")
                return gw
            } catch (e: Exception) {
                Log.w(TAG, "parse $loc failed: ${e.message}")
            }
        }
        return null
    }

    private fun ssdpSearch(st: String): Set<String> {
        val locations = mutableSetOf<String>()
        val socket = DatagramSocket()
        socket.soTimeout = SSDP_TIMEOUT_MS
        val msg = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: $SSDP_MX\r\n")
            append("ST: $st\r\n")
            append("\r\n")
        }
        val data = msg.toByteArray()
        val packet = DatagramPacket(data, data.size, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT)
        socket.send(packet)
        val deadline = System.currentTimeMillis() + SSDP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                val buf = ByteArray(4096)
                val respPacket = DatagramPacket(buf, buf.size)
                socket.soTimeout = (deadline - System.currentTimeMillis()).coerceAtLeast(200).toInt()
                socket.receive(respPacket)
                val resp = String(respPacket.data, 0, respPacket.length)
                // case-insensitive LOCATION
                val loc = Regex("(?i)LOCATION:\\s*(\\S+)").find(resp)?.groupValues?.get(1)?.trim()
                if (loc != null) locations.add(loc)
            } catch (_: java.net.SocketTimeoutException) {
                break
            } catch (_: Exception) { break }
        }
        socket.close()
        return locations
    }

    private fun parseDeviceDescription(locationUrl: String): Gateway? {
        val url = URL(locationUrl)
        val base = "${url.protocol}://${url.host}:${if (url.port != -1) url.port else url.defaultPort}"
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 4000; conn.readTimeout = 4000
        conn.requestMethod = "GET"
        val xml = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = true
        val doc = dbf.newDocumentBuilder().parse(xml.byteInputStream())
        doc.documentElement.normalize()

        // Find service WANIPConnection or WANPPPConnection
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val node = services.item(i)
            var serviceType: String? = null
            var controlURL: String? = null
            val children = node.childNodes
            for (j in 0 until children.length) {
                val c = children.item(j)
                when (c.nodeName) {
                    "serviceType" -> serviceType = c.textContent.trim()
                    "controlURL" -> controlURL = c.textContent.trim()
                }
            }
            if (serviceType != null && controlURL != null &&
                (serviceType.contains("WANIPConnection") || serviceType.contains("WANPPPConnection"))
            ) {
                val controlFull = when {
                    controlURL.startsWith("http") -> controlURL
                    controlURL.startsWith("/") -> base + controlURL
                    else -> "$base/$controlURL"
                }
                return Gateway(locationUrl, controlFull, serviceType, getLocalIp())
            }
        }
        return null
    }

    // ---- SOAP ----

    private fun addPortMapping(gw: Gateway, localIp: String, port: Int, desc: String): Boolean {
        val args = buildString {
            append("<NewRemoteHost></NewRemoteHost>")
            append("<NewExternalPort>$port</NewExternalPort>")
            append("<NewProtocol>TCP</NewProtocol>")
            append("<NewInternalPort>$port</NewInternalPort>")
            append("<NewInternalClient>$localIp</NewInternalClient>")
            append("<NewEnabled>1</NewEnabled>")
            append("<NewPortMappingDescription>$desc</NewPortMappingDescription>")
            append("<NewLeaseDuration>0</NewLeaseDuration>")
        }
        val body = soapBody(gw.serviceType, "AddPortMapping", args)
        val resp = soapRequest(gw.controlUrl, gw.serviceType, "AddPortMapping", body)
        return !resp.contains("errorCode") && !resp.contains("soap:Fault")
    }

    private fun deletePortMapping(gw: Gateway, port: Int): Boolean {
        val args = "<NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort><NewProtocol>TCP</NewProtocol>"
        val body = soapBody(gw.serviceType, "DeletePortMapping", args)
        val resp = soapRequest(gw.controlUrl, gw.serviceType, "DeletePortMapping", body)
        return !resp.contains("errorCode")
    }

    private fun soapBody(serviceType: String, action: String, args: String): String = """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
        <s:Body><u:$action xmlns:u="$serviceType">$args</u:$action></s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun soapRequest(controlUrl: String, serviceType: String, action: String, body: String): String {
        val url = URL(controlUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPAction", "\"$serviceType#$action\"")
        conn.setRequestProperty("Content-Length", body.toByteArray().size.toString())
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        return text
    }

    private fun getLocalIp(): String {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }
}
