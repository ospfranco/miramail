package com.ospfranco.trustmail.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.floor
import kotlin.math.max

/**
 * Earliest issuance among a domain's certificates in Certificate Transparency logs.
 * A null history means the lookup failed; `firstIssued == null` means no certificates are logged.
 */
data class CertificateHistory(val firstIssued: Instant?)

sealed interface TlsProbeResult {
    data class Ok(val trusted: Boolean, val trustError: String?, val leaf: Certificate) : TlsProbeResult
    data class Failure(val error: String) : TlsProbeResult
}

data class PublicDnsAnswer(val exists: Boolean, val addresses: List<String>)

/**
 * A tiny async cache so concurrent callers share one in-flight request per key.
 * Newsletters link the same few hosts many times, and the same sender keeps sending them.
 */
class TtlCache<V>(
    private val ttlMs: Long = 10 * 60 * 1000,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Entry<V>(val expires: Long, val value: Deferred<V>)

    private val entries = HashMap<String, Entry<V>>()

    suspend fun get(key: String, load: suspend () -> V): V {
        val entry = synchronized(entries) {
            entries[key]?.takeIf { it.expires > System.currentTimeMillis() }
                ?: Entry(System.currentTimeMillis() + ttlMs, scope.async { load() }).also { entries[key] = it }
        }
        return entry.value.await()
    }
}

object LinkChecks {
    const val TIMEOUT_MS = 5000
    private const val DAY_SECONDS = 24 * 60 * 60.0

    /** A domain whose certificate history is younger than this is likely freshly registered (throwaway). */
    const val NEW_DOMAIN_DAYS = 15.0

    /** A subdomain certificate this young, on an established domain, suggests a just-created phishing subdomain. */
    const val NEW_SUBDOMAIN_CERTIFICATE_DAYS = 2.0

    /**
     * Organizations that issue the vast majority of publicly trusted TLS certificates, matched as a
     * case-insensitive prefix of the leaf certificate's issuer O (falling back to CN).
     * Brands like RapidSSL, GeoTrust and Thawte issue under "DigiCert Inc".
     */
    val KNOWN_CERTIFICATE_ISSUERS = listOf(
        "Let's Encrypt",
        "Google Trust Services",
        "DigiCert",
        "Sectigo",
        "ZeroSSL",
        "GlobalSign",
        "Amazon",
        "Microsoft Corporation",
        "GoDaddy.com",
        "Starfield Technologies",
        "Entrust",
        "IdenTrust",
        "Cloudflare",
        "Apple Inc",
        "SSL Corporation",
        "Certainly",
        "Actalis",
        "Buypass",
    )

    // region Network (cached)

    private val tlsCache = TtlCache<TlsProbeResult>()
    private val historyCache = TtlCache<CertificateHistory?>()
    private val checksCache = TtlCache<List<LinkCheck>>()

    private val json = Json { ignoreUnknownKeys = true }

    // The call timeout covers the whole request, like the abort timer it replaces.
    private val http = OkHttpClient.Builder().callTimeout(Duration.ofMillis(TIMEOUT_MS.toLong())).build()

    private suspend fun probeTls(host: String): TlsProbeResult = tlsCache.get(host) {
        try {
            val result = TlsInspector.inspect(host, 443, TIMEOUT_MS)
            val leaf = result.chain.firstOrNull() ?: return@get TlsProbeResult.Failure("Server sent no certificate")
            TlsProbeResult.Ok(result.trusted, result.trustError, parseCertificate(leaf))
        } catch (e: Exception) {
            TlsProbeResult.Failure(e.message ?: e.toString())
        }
    }

    @Serializable
    private class Issuance(val not_before: String)

    /**
     * Asks Cert Spotter for the domain's (and its subdomains') certificates. Every publicly trusted
     * certificate must be logged to CT, so the oldest one approximates how long the domain has served
     * HTTPS. Cert Spotter only returns unexpired certificates, so this is a lower bound on the
     * domain's age, which is enough to tell a days-old domain from an established one.
     *
     * TODO(backend): unauthenticated Cert Spotter allows 100 requests/hour per IP. Move this lookup
     * behind our backend with an API key and shared cache. See FUTURE_CHANGES.md.
     */
    private suspend fun fetchCertificateHistory(domain: String): CertificateHistory? = historyCache.get(domain) {
        try {
            val url = "https://api.certspotter.com/v1/issuances".toHttpUrl().newBuilder()
                .addQueryParameter("domain", domain)
                .addQueryParameter("include_subdomains", "true")
                .build()
            val body = httpGet(url) ?: return@get null
            // Results come oldest-logged first, so the first page holds the earliest issuances.
            val issuances = json.decodeFromString<List<Issuance>>(body)
            if (issuances.isEmpty()) return@get CertificateHistory(null)
            val earliest = issuances.mapNotNull { runCatching { OffsetDateTime.parse(it.not_before).toInstant() }.getOrNull() }.minOrNull()
            earliest?.let { CertificateHistory(it) }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun httpGet(url: HttpUrl, accept: String? = null): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { accept?.let { header("accept", it) } }.build()
        http.newCall(request).execute().use { response -> if (response.isSuccessful) response.body.string() else null }
    }

    @Serializable
    private class DohAnswer(val type: Int, val data: String)

    @Serializable
    private class DohResponse(val Status: Int, val Answer: List<DohAnswer>? = null)

    private const val RECORD_TYPE_A = 1
    private const val RECORD_TYPE_AAAA = 28
    private const val NX_DOMAIN = 3

    /** Looks up A and AAAA over DNS-over-HTTPS, bypassing the device's (possibly tampered) resolver. */
    private suspend fun resolvePublicly(host: String): PublicDnsAnswer? = try {
        coroutineScope {
            suspend fun query(type: Int): DohResponse {
                val url = "https://cloudflare-dns.com/dns-query".toHttpUrl().newBuilder()
                    .addQueryParameter("name", host)
                    .addQueryParameter("type", type.toString())
                    .build()
                return json.decodeFromString(httpGet(url, "application/dns-json") ?: error("DNS-over-HTTPS failed"))
            }
            val a = async { query(RECORD_TYPE_A) }
            val aaaa = async { query(RECORD_TYPE_AAAA) }
            val answers = listOf(a.await(), aaaa.await())
            val addresses = answers.flatMap { response ->
                response.Answer.orEmpty().filter { it.type == RECORD_TYPE_A || it.type == RECORD_TYPE_AAAA }.map { it.data }
            }
            PublicDnsAnswer(exists = answers[0].Status != NX_DOMAIN, addresses = addresses)
        }
    } catch (e: Exception) {
        null
    }

    // endregion

    // region Decisions (pure, unit-tested)

    private val mappedV4 = Regex("""(\d+\.\d+\.\d+\.\d+)$""")
    private val v4 = Regex("""(\d+)\.(\d+)\.\d+\.\d+""")

    /** Canonical form so the two resolvers' answers can be compared (IPv6 zero-compression, scope ids). */
    fun normalizeIp(ip: String): String {
        val address = ip.substringBefore('%').lowercase()
        mappedV4.find(address)?.let { return it.groupValues[1] }
        if (':' !in address) return address

        val halves = address.split("::")
        val headGroups = if (halves[0].isEmpty()) emptyList() else halves[0].split(":")
        val groups = if (halves.size > 1) {
            val tailGroups = if (halves[1].isEmpty()) emptyList() else halves[1].split(":")
            headGroups + List(max(0, 8 - headGroups.size - tailGroups.size)) { "0" } + tailGroups
        } else {
            headGroups
        }
        return groups.joinToString(":") { (it.toIntOrNull(16) ?: 0).toString(16) }
    }

    /** Loopback, private, link-local, CGNAT, multicast and other addresses a public site never uses. */
    fun isReservedIp(ip: String): Boolean {
        v4.matchEntire(ip)?.let { match ->
            val a = match.groupValues[1].toInt()
            val b = match.groupValues[2].toInt()
            return a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168)
        }
        return ip == "0:0:0:0:0:0:0:1" || ip == "0:0:0:0:0:0:0:0" ||
            ip.startsWith("fc") || ip.startsWith("fd") ||
            listOf("fe8", "fe9", "fea", "feb").any { ip.startsWith(it) }
    }

    /**
     * Compares the device's DNS answer with a public DNS-over-HTTPS answer. Different addresses alone
     * are normal for CDNs (answers vary by resolver location), so a mismatch only counts as spoofing
     * when the server can't also prove its identity with a valid certificate.
     */
    fun checkDns(host: String, deviceAddresses: List<String>, publicDns: PublicDnsAnswer?, tls: TlsProbeResult?): LinkCheck {
        val kind = LinkCheckKind.DnsSpoofing
        val deviceIps = deviceAddresses.map(::normalizeIp)

        if (publicDns == null) {
            return LinkCheck(kind, Verdict.Warning, "Couldn't reach public DNS to compare answers.")
        }
        val publicIps = publicDns.addresses.map(::normalizeIp)

        if (deviceIps.isEmpty() && publicIps.isEmpty()) {
            return LinkCheck(kind, Verdict.Warning, "$host doesn't exist or has no addresses.")
        }
        if (!publicDns.exists && deviceIps.isNotEmpty()) {
            return LinkCheck(kind, Verdict.Danger, "Your network resolves $host, but public DNS says the domain doesn't exist.")
        }
        if (deviceIps.any(::isReservedIp) && !publicIps.any(::isReservedIp)) {
            val reserved = deviceIps.first(::isReservedIp)
            return LinkCheck(kind, Verdict.Danger, "Your network points $host to a private address ($reserved) instead of its public one.")
        }
        if (publicIps.isNotEmpty() && deviceIps.none { it in publicIps }) {
            if (tls is TlsProbeResult.Ok && tls.trusted) {
                return LinkCheck(kind, Verdict.Safe, "DNS answers differ (normal for CDNs), but the server proved its identity with a valid certificate.")
            }
            return if (tls != null) {
                LinkCheck(kind, Verdict.Danger, "Your network and public DNS disagree on this domain's address, and the server can't prove its identity.")
            } else {
                LinkCheck(kind, Verdict.Warning, "Your network and public DNS disagree on this domain's address, and the link isn't encrypted to confirm it.")
            }
        }
        return LinkCheck(kind, Verdict.Safe, "Your network and public DNS agree on the address.")
    }

    private fun ageInDays(date: Instant, now: Instant): Double =
        Duration.between(date, now).toMillis() / 1000.0 / DAY_SECONDS

    fun formatAge(days: Double): String {
        if (days < 1) return "${max(1L, Math.round(days * 24))} hours"
        return "${floor(days).toInt()} days"
    }

    fun checkDomainAge(domain: String, history: CertificateHistory?, encrypted: Boolean, now: Instant = Instant.now()): LinkCheck {
        val kind = LinkCheckKind.DomainAge
        if (history == null) return LinkCheck(kind, Verdict.Warning, "Couldn't look up $domain's certificate history.")
        val firstIssued = history.firstIssued
            // Every trusted certificate must be logged, so an HTTPS site with none is brand new.
            // An HTTP-only site may simply never have had one.
            ?: return if (encrypted) {
                LinkCheck(kind, Verdict.Danger, "$domain has no certificates in public logs. It's likely brand new.")
            } else {
                LinkCheck(kind, Verdict.Warning, "$domain has never had an HTTPS certificate, so its age can't be checked.")
            }
        val age = ageInDays(firstIssued, now)
        return if (age < NEW_DOMAIN_DAYS) {
            LinkCheck(kind, Verdict.Danger, "$domain's first certificate was issued only ${formatAge(age)} ago. New domains are often used for phishing.")
        } else {
            LinkCheck(kind, Verdict.Safe, "$domain has had certificates for at least ${formatAge(age)}.")
        }
    }

    /** Shown instead of a domain-age result for a very well known domain (see [KnownDomains]). */
    fun wellKnownDomainCheck(domain: String): LinkCheck =
        LinkCheck(LinkCheckKind.DomainAge, Verdict.Safe, "$domain is a well-known domain, so its age isn't checked.")

    /**
     * The domain-age result for [domain]. For a very well known domain there is nothing to look up, so
     * [fetchHistory] (the Certificate Transparency request) is never called.
     */
    suspend fun domainAgeResult(
        domain: String,
        encrypted: Boolean,
        known: KnownDomains = KnownDomains.shared,
        now: Instant = Instant.now(),
        fetchHistory: suspend () -> CertificateHistory?,
    ): LinkCheck {
        if (known.contains(domain)) return wellKnownDomainCheck(domain)
        return checkDomainAge(domain, fetchHistory(), encrypted, now)
    }

    fun checkTls(host: String, apex: String, hostTls: TlsProbeResult, apexTls: TlsProbeResult?, now: Instant = Instant.now()): List<LinkCheck> {
        if (hostTls !is TlsProbeResult.Ok) {
            val error = (hostTls as TlsProbeResult.Failure).error
            return listOf(LinkCheck(LinkCheckKind.TlsConnection, Verdict.Warning, "Couldn't open a secure connection to check the certificate ($error)."))
        }
        val leaf = hostTls.leaf
        val checks = ArrayList<LinkCheck>()

        val matches = certificateMatchesHost(leaf, host)
        val otherSite = leaf.dnsNames.take(3).joinToString(", ")
        checks.add(
            LinkCheck(
                LinkCheckKind.CertificateHostname,
                if (matches) Verdict.Safe else Verdict.Danger,
                if (matches) {
                    "Certificate is valid for $host."
                } else {
                    "Certificate is for ${otherSite.ifEmpty { leaf.subjectCommonName ?: "another site" }}, not $host."
                },
            ),
        )

        val issuer = leaf.issuerOrg ?: leaf.issuerCommonName ?: "Unknown issuer"
        val known = KNOWN_CERTIFICATE_ISSUERS.any { issuer.lowercase().startsWith(it.lowercase()) }
        checks.add(
            LinkCheck(
                LinkCheckKind.CertificateIssuer,
                if (!hostTls.trusted) Verdict.Danger else if (known) Verdict.Safe else Verdict.Warning,
                if (!hostTls.trusted) {
                    "Your device doesn't trust this certificate${hostTls.trustError?.let { ": $it" } ?: "."}"
                } else if (known) {
                    "Issued by $issuer."
                } else {
                    "Issued by $issuer, which isn't a common certificate authority."
                },
            ),
        )

        if (host != apex && apexTls is TlsProbeResult.Ok) {
            val sameCertificate = apexTls.leaf.der == leaf.der
            val hostAge = ageInDays(leaf.notBefore, now)
            val fresh = !sameCertificate && hostAge < NEW_SUBDOMAIN_CERTIFICATE_DAYS
            checks.add(
                LinkCheck(
                    LinkCheckKind.SubdomainCertificate,
                    if (fresh) Verdict.Danger else Verdict.Safe,
                    if (sameCertificate) {
                        "$host uses the same certificate as $apex."
                    } else if (fresh) {
                        "$host got its own certificate only ${formatAge(hostAge)} ago, on an otherwise established domain."
                    } else {
                        "$host has its own certificate, issued ${formatAge(hostAge)} ago."
                    },
                ),
            )
        }

        return checks
    }

    // endregion

    private suspend fun runChecks(host: String, encrypted: Boolean): List<LinkCheck> = coroutineScope {
        val apex = PublicSuffixList.shared.registrableDomain(host) ?: host
        val hostTls = async { if (encrypted) probeTls(host) else null }
        val apexTls = async { if (encrypted && apex != host) probeTls(apex) else null }
        val ageCheck = async { domainAgeResult(apex, encrypted) { fetchCertificateHistory(apex) } }
        val hostResult = hostTls.await()
        val apexResult = apexTls.await()
        val ageResult = ageCheck.await()

        val device = async { TlsInspector.resolve(host) }
        val publicDns = async { resolvePublicly(host) }
        val dns = checkDns(host, device.await(), publicDns.await(), hostResult)

        buildList {
            add(dns)
            add(ageResult)
            if (hostResult != null) addAll(checkTls(host, apex, hostResult, apexResult))
        }
    }

    /**
     * DNS and TLS checks for a link's host. Only performs a TLS handshake; never requests the URL itself.
     *
     * TODO(backend): these lookups run from the user's device, so the link's server sees the user's IP
     * and when they opened the email (per-recipient subdomains turn this into a read receipt), and
     * Cloudflare sees every looked-up hostname. Run them from our backend instead. See FUTURE_CHANGES.md.
     */
    suspend fun runNetworkChecks(host: String, encrypted: Boolean): List<LinkCheck> =
        checksCache.get("${if (encrypted) "https" else "http"}://$host") { runChecks(host, encrypted) }
}
