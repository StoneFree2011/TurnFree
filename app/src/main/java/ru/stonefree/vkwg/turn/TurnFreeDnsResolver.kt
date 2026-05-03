package ru.stonefree.vkwg.turn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object TurnFreeDnsResolver {

    fun resolvePeerAddress(rawPeer: String): String {
        val parsed = parseHostPort(rawPeer) ?: return rawPeer
        val resolvedHost = resolveHost(parsed.host) ?: return rawPeer
        return if (parsed.bracketIpv6 || resolvedHost.contains(':')) {
            "[$resolvedHost]:${parsed.port}"
        } else {
            "$resolvedHost:${parsed.port}"
        }
    }

    private fun resolveHost(host: String): String? {
        if (host.isBlank() || isIpLiteral(host)) {
            return host
        }

        return runCatching {
            InetAddress.getByName(host).hostAddress
        }.getOrNull() ?: resolveViaDnsServer(host)
    }

    private fun isIpLiteral(host: String): Boolean {
        return host.all { it.isDigit() || it == '.' } || host.contains(':')
    }

    private fun parseHostPort(value: String): HostPort? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return null
        }

        if (trimmed.startsWith("[") && trimmed.contains("]:")) {
            val host = trimmed.substringAfter("[").substringBefore("]")
            val port = trimmed.substringAfter("]:")
            return port.takeIf { it.isNotBlank() }?.let { HostPort(host, it, bracketIpv6 = true) }
        }

        if (trimmed.count { it == ':' } != 1) {
            return null
        }

        val host = trimmed.substringBefore(':')
        val port = trimmed.substringAfter(':')
        return if (host.isNotBlank() && port.isNotBlank()) {
            HostPort(host, port, bracketIpv6 = false)
        } else {
            null
        }
    }

    private fun resolveViaDnsServer(host: String): String? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket().apply {
                soTimeout = DNS_TIMEOUT_MS
            }

            val transactionId = (0..0xFFFF).random()
            val query = buildQuery(host, transactionId)
            val request = DatagramPacket(
                query,
                query.size,
                InetAddress.getByName(FALLBACK_DNS_SERVER),
                DNS_PORT,
            )

            socket.send(request)

            val buffer = ByteArray(512)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            parseResponse(buffer, response.length, transactionId)
        } catch (_: Exception) {
            null
        } finally {
            socket?.close()
        }
    }

    private fun buildQuery(host: String, transactionId: Int): ByteArray {
        val bytes = mutableListOf<Byte>()

        bytes += (transactionId shr 8).toByte()
        bytes += (transactionId and 0xFF).toByte()
        bytes += 0x01.toByte()
        bytes += 0x00.toByte()
        bytes += 0x00.toByte()
        bytes += 0x01.toByte()
        repeat(6) { bytes += 0x00.toByte() }

        host.split('.')
            .filter { it.isNotBlank() }
            .forEach { label ->
                bytes += label.length.toByte()
                label.forEach { char -> bytes += char.code.toByte() }
            }

        bytes += 0x00.toByte()
        bytes += 0x00.toByte()
        bytes += 0x01.toByte()
        bytes += 0x00.toByte()
        bytes += 0x01.toByte()

        return bytes.toByteArray()
    }

    private fun parseResponse(data: ByteArray, length: Int, expectedId: Int): String? {
        if (length < 12) {
            return null
        }

        val actualId = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        if (actualId != expectedId) {
            return null
        }

        val questions = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val answers = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (answers == 0) {
            return null
        }

        var position = 12
        repeat(questions) {
            position = skipName(data, position)
            position += 4
        }

        repeat(answers) {
            position = skipName(data, position)
            if (position + 10 > length) {
                return null
            }

            val type = ((data[position].toInt() and 0xFF) shl 8) or (data[position + 1].toInt() and 0xFF)
            val rdLength = ((data[position + 8].toInt() and 0xFF) shl 8) or (data[position + 9].toInt() and 0xFF)
            position += 10

            if (type == 1 && rdLength == 4 && position + 4 <= length) {
                return listOf(
                    data[position].toInt() and 0xFF,
                    data[position + 1].toInt() and 0xFF,
                    data[position + 2].toInt() and 0xFF,
                    data[position + 3].toInt() and 0xFF,
                ).joinToString(".")
            }

            position += rdLength
        }

        return null
    }

    private fun skipName(data: ByteArray, start: Int): Int {
        var position = start
        while (position < data.size) {
            val value = data[position].toInt() and 0xFF
            if (value == 0) {
                return position + 1
            }
            if ((value and 0xC0) == 0xC0) {
                return position + 2
            }
            position += value + 1
        }
        return position
    }

    private data class HostPort(
        val host: String,
        val port: String,
        val bracketIpv6: Boolean,
    )

    private const val FALLBACK_DNS_SERVER = "77.88.8.8"
    private const val DNS_PORT = 53
    private const val DNS_TIMEOUT_MS = 2_000
}
