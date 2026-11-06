package com.phonemic

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object LanEndpoint {
    fun parse(input: String): HttpUrl {
        val value = input.trim()
        require(value.isNotEmpty()) { "Enter a computer address" }
        val authority = if (!value.contains("://") && value.count { it == ':' } > 1 && !value.startsWith("[")) "[$value]" else value
        val parsed = (if (authority.contains("://")) authority else "https://$authority").toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid computer address")
        require(parsed.scheme == "https" && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null) { "Use an IP address or hostname, optionally with a port" }
        // An omitted port always means the receiver port, including https:// addresses.
        val explicitPort = authority.substringAfter("://", authority).let {
            if (it.startsWith("[")) it.substringAfter(']', "").startsWith(":") else it.substringBefore('/').contains(':')
        }
        return parsed.newBuilder().port(if (explicitPort) parsed.port else 8765).build()
    }

    fun address(url: HttpUrl): String {
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        return if (url.port == 8765) host else "$host:${url.port}"
    }
}
