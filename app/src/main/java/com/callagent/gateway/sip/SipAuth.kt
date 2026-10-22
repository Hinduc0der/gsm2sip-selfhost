package com.callagent.gateway.sip

import java.security.MessageDigest

/**
 * SIP Digest Authentication (RFC 2617).
 * Ported from the Python SIP implementation.
 */
object SipAuth {

    /** Parse WWW-Authenticate header parameters */
    fun parseChallenge(msg: SipMessage): AuthParams? {
        val authHeader = msg.header("www-authenticate") ?: msg.header("proxy-authenticate")
            ?: return null

        if (!authHeader.lowercase().startsWith("digest")) return null

        val params = mutableMapOf<String, String>()
        // Strip "Digest " prefix (case-insensitive)
        val digestIdx = authHeader.lowercase().indexOf("digest")
        val paramStr = if (digestIdx >= 0) authHeader.substring(digestIdx + 6).trim() else return null
        paramStr.split(",")
            .forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val key = pair.substring(0, eq).trim().lowercase()
                    val value = pair.substring(eq + 1).trim().trim('"')
                    params[key] = value
                }
            }

        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        return AuthParams(realm, nonce, params["opaque"], params["qop"])
    }

    /** Build Authorization header for REGISTER */
    fun buildAuthHeader(
        method: String,
        uri: String,
        username: String,
        password: String,
        params: AuthParams
    ): String {
        val ha1 = md5("$username:${params.realm}:$password")
        val ha2 = md5("$method:$uri")
        val response = md5("$ha1:${params.nonce}:$ha2")

        return buildString {
            append("Authorization: Digest username=\"$username\", ")
            append("realm=\"${params.realm}\", ")
            append("nonce=\"${params.nonce}\", ")
            append("uri=\"$uri\", ")
            append("response=\"$response\", ")
            append("algorithm=MD5")
            if (params.opaque != null) append(", opaque=\"${params.opaque}\"")
            append("\r\n")
        }
    }

    /** Build Authorization header for INVITE */
    fun buildInviteAuthHeader(
        uri: String,
        username: String,
        password: String,
        params: AuthParams
    ): String = buildAuthHeader("INVITE", uri, username, password, params)

    // ── Registrar (server) side ─────────────────────────
    // Used when this gateway is the built-in registrar and challenges an inbound REGISTER.

    private val random = java.security.SecureRandom()

    /** Parse a client's Authorization header into its digest fields. */
    fun parseAuthorization(msg: SipMessage): Map<String, String>? {
        val h = msg.header("authorization") ?: msg.header("proxy-authorization") ?: return null
        if (!h.trim().lowercase().startsWith("digest")) return null
        val idx = h.lowercase().indexOf("digest")
        val params = mutableMapOf<String, String>()
        h.substring(idx + 6).split(",").forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) params[pair.substring(0, eq).trim().lowercase()] =
                pair.substring(eq + 1).trim().trim('"')
        }
        return params
    }

    /** A fresh opaque nonce for a challenge we issue as the registrar. */
    fun newNonce(): String {
        val b = ByteArray(16); random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** WWW-Authenticate header line for a 401 we send as the registrar. */
    fun challengeHeader(realm: String, nonce: String): String =
        "WWW-Authenticate: Digest realm=\"$realm\", nonce=\"$nonce\", qop=\"auth\", algorithm=MD5"

    /**
     * Verify a client's parsed Authorization [params] against [password] for [method].
     * Recomputes the digest from the username the client sent and our shared password.
     * [nonceValid] guards against replayed/expired nonces. Constant-time response compare.
     */
    fun verify(
        params: Map<String, String>,
        method: String,
        password: String,
        nonceValid: (String) -> Boolean
    ): Boolean {
        val username = params["username"] ?: return false
        val realm = params["realm"] ?: return false
        val nonce = params["nonce"] ?: return false
        val uri = params["uri"] ?: return false
        val response = params["response"] ?: return false
        if (!nonceValid(nonce)) return false
        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("$method:$uri")
        val qop = params["qop"]; val nc = params["nc"]; val cnonce = params["cnonce"]
        val expected = if (qop != null && nc != null && cnonce != null)
            md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        return constantTimeEquals(expected, response)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class AuthParams(
        val realm: String,
        val nonce: String,
        val opaque: String?,
        val qop: String?
    )
}
