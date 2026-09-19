package com.etlshadowtest.auth

import com.etlshadowtest.api.ErrorBody
import com.etlshadowtest.config.ShadowProperties
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/** Who is calling: what Pipeline and Targets the caller's token authorizes. */
interface Principal {
    fun canAccess(pipeline: String): Boolean

    fun canUseTarget(name: String): Boolean
}

fun interface TokenStore {
    fun find(token: String): Principal?
}

/** A token authorizes exactly one Pipeline and its list of Targets. */
class PipelinePrincipal(private val pipeline: String, private val targets: Set<String>) : Principal {
    override fun canAccess(pipeline: String) = pipeline == this.pipeline

    override fun canUseTarget(name: String) = name in targets
}

/** Tokens from service configuration (provisioned from Kubernetes secrets). */
@Component
class ConfiguredTokenStore(props: ShadowProperties) : TokenStore {
    private class Entry(val digest: ByteArray, val principal: Principal)

    private val entries = props.tokens.map { Entry(sha256(it.token), PipelinePrincipal(it.pipeline, it.targets.toSet())) }

    /** Compares digests in constant time, and always looks at every token, so timing reveals nothing about a near miss. */
    override fun find(token: String): Principal? {
        val candidate = sha256(token)
        var match: Principal? = null
        for (entry in entries) if (MessageDigest.isEqual(entry.digest, candidate)) match = entry.principal
        return match
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
}

const val PRINCIPAL_ATTRIBUTE = "shadow.principal"

/** Every API call needs a bearer token; health checks do not. Tokens are never logged or echoed. */
@Component
class TokenAuthFilter(private val tokens: TokenStore, private val mapper: ObjectMapper) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader("Authorization")
        val token = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }
        val principal = token?.let { tokens.find(it) }
        if (principal == null) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.setHeader("WWW-Authenticate", "Bearer")
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            mapper.writeValue(response.outputStream, ErrorBody("A valid bearer token is required"))
            return
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal)
        chain.doFilter(request, response)
    }
}
