import { getDomain } from 'tldts';

import TlsInspector from '../../modules/tls-inspector';
import { LinkCheckKind, Verdict, type LinkCheck } from './verdict';
import { certificateMatchesHost, parseCertificate, type Certificate } from './x509';

const TIMEOUT_MS = 5000;
const DAY_MS = 24 * 60 * 60 * 1000;
/** A domain whose certificate history is younger than this is likely freshly registered (throwaway). */
const NEW_DOMAIN_DAYS = 15;
/** A subdomain certificate this young, on an established domain, suggests a just-created phishing subdomain. */
const NEW_SUBDOMAIN_CERTIFICATE_DAYS = 2;

/**
 * Organizations that issue the vast majority of publicly trusted TLS certificates, matched as a
 * case-insensitive prefix of the leaf certificate's issuer O (falling back to CN).
 * Brands like RapidSSL, GeoTrust and Thawte issue under "DigiCert Inc".
 */
export const KNOWN_CERTIFICATE_ISSUERS = [
  "Let's Encrypt",
  'Google Trust Services',
  'DigiCert',
  'Sectigo',
  'ZeroSSL',
  'GlobalSign',
  'Amazon',
  'Microsoft Corporation',
  'GoDaddy.com',
  'Starfield Technologies',
  'Entrust',
  'IdenTrust',
  'Cloudflare',
  'Apple Inc',
  'SSL Corporation',
  'Certainly',
  'Actalis',
  'Buypass',
];

enum DnsStatus {
  NoError = 0,
  NxDomain = 3,
}

enum DnsRecordType {
  A = 1,
  AAAA = 28,
}

/**
 * Earliest issuance among a domain's certificates in Certificate Transparency logs.
 * `null` means the lookup failed; `firstIssued: null` means no certificates are logged.
 */
type CertificateHistory = { firstIssued: Date | null } | null;

type TlsProbe =
  | { ok: true; trusted: boolean; trustError: string | null; leaf: Certificate }
  | { ok: false; error: string };

// Newsletters link the same few hosts many times, and the same sender keeps sending them.
const CACHE_TTL_MS = 10 * 60 * 1000;
const tlsCache = new Map<string, { expires: number; value: Promise<TlsProbe> }>();
const historyCache = new Map<string, { expires: number; value: Promise<CertificateHistory> }>();
const checksCache = new Map<string, { expires: number; value: Promise<LinkCheck[]> }>();

function cached<T>(cache: Map<string, { expires: number; value: Promise<T> }>, key: string, load: () => Promise<T>) {
  const entry = cache.get(key);
  if (entry && entry.expires > Date.now()) return entry.value;
  const value = load();
  cache.set(key, { expires: Date.now() + CACHE_TTL_MS, value });
  return value;
}

function probeTls(host: string): Promise<TlsProbe> {
  return cached(tlsCache, host, () =>
    TlsInspector.inspectTls(host, 443, TIMEOUT_MS)
      .then((result): TlsProbe => {
        if (!result.chain.length) return { ok: false, error: 'Server sent no certificate' };
        return {
          ok: true,
          trusted: result.trusted,
          trustError: result.trustError ?? null,
          leaf: parseCertificate(result.chain[0]),
        };
      })
      .catch((e: unknown): TlsProbe => ({ ok: false, error: e instanceof Error ? e.message : String(e) })),
  );
}

/**
 * Asks Cert Spotter for the domain's (and its subdomains') certificates. Every publicly trusted
 * certificate must be logged to CT, so the oldest one approximates how long the domain has served
 * HTTPS. Cert Spotter only returns unexpired certificates, so this is a lower bound on the
 * domain's age, which is enough to tell a days-old domain from an established one.
 *
 * TODO(backend): unauthenticated Cert Spotter allows 100 requests/hour per IP. Move this lookup
 * behind our backend with an API key and shared cache. See FUTURE_CHANGES.md.
 */
function fetchCertificateHistory(domain: string): Promise<CertificateHistory> {
  return cached(historyCache, domain, async () => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
    try {
      const res = await fetch(
        `https://api.certspotter.com/v1/issuances?domain=${encodeURIComponent(domain)}&include_subdomains=true`,
        { signal: controller.signal },
      );
      if (!res.ok) return null;
      // Results come oldest-logged first, so the first page holds the earliest issuances.
      const issuances = (await res.json()) as { not_before: string }[];
      const earliest = Math.min(...issuances.map((i) => Date.parse(i.not_before)));
      return { firstIssued: issuances.length ? new Date(earliest) : null };
    } catch {
      return null;
    } finally {
      clearTimeout(timer);
    }
  });
}

/** Looks up A and AAAA over DNS-over-HTTPS, bypassing the device's (possibly tampered) resolver. */
async function resolvePublicly(host: string): Promise<{ exists: boolean; addresses: string[] } | null> {
  const query = async (type: DnsRecordType) => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
    try {
      const res = await fetch(`https://cloudflare-dns.com/dns-query?name=${encodeURIComponent(host)}&type=${type}`, {
        headers: { accept: 'application/dns-json' },
        signal: controller.signal,
      });
      return (await res.json()) as { Status: number; Answer?: { type: number; data: string }[] };
    } finally {
      clearTimeout(timer);
    }
  };
  try {
    const answers = await Promise.all([query(DnsRecordType.A), query(DnsRecordType.AAAA)]);
    const addresses = answers.flatMap((a) =>
      (a.Answer ?? []).filter((r) => r.type === DnsRecordType.A || r.type === DnsRecordType.AAAA).map((r) => r.data),
    );
    return { exists: answers[0].Status !== DnsStatus.NxDomain, addresses };
  } catch {
    return null;
  }
}

/** Canonical form so the two resolvers' answers can be compared (IPv6 zero-compression, scope ids). */
function normalizeIp(ip: string) {
  const address = ip.split('%')[0].toLowerCase();
  const mappedV4 = /(\d+\.\d+\.\d+\.\d+)$/.exec(address);
  if (mappedV4) return mappedV4[1];
  if (!address.includes(':')) return address;
  const [head, tail] = address.split('::');
  const headGroups = head ? head.split(':') : [];
  const tailGroups = tail ? tail.split(':') : [];
  const groups =
    tail === undefined
      ? headGroups
      : [...headGroups, ...Array<string>(8 - headGroups.length - tailGroups.length).fill('0'), ...tailGroups];
  return groups.map((g) => parseInt(g, 16).toString(16)).join(':');
}

/** Loopback, private, link-local, CGNAT, multicast and other addresses a public site never uses. */
function isReservedIp(ip: string) {
  const v4 = /^(\d+)\.(\d+)\.\d+\.\d+$/.exec(ip);
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])];
    return (
      a === 0 || a === 10 || a === 127 || a >= 224 ||
      (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168)
    );
  }
  return ip === '0:0:0:0:0:0:0:1' || ip === '0:0:0:0:0:0:0:0' || /^f[cd]/.test(ip) || /^fe[89ab]/.test(ip);
}

/**
 * Compares the device's DNS answer with a public DNS-over-HTTPS answer. Different addresses alone
 * are normal for CDNs (answers vary by resolver location), so a mismatch only counts as spoofing
 * when the server can't also prove its identity with a valid certificate.
 */
async function checkDns(host: string, tls: TlsProbe | null): Promise<LinkCheck> {
  const kind = LinkCheckKind.DnsSpoofing;
  const [device, publicDns] = await Promise.all([
    TlsInspector.resolveHost(host).catch(() => [] as string[]),
    resolvePublicly(host),
  ]);
  const deviceIps = device.map(normalizeIp);

  if (!publicDns) {
    return { kind, verdict: Verdict.Warning, detail: "Couldn't reach public DNS to compare answers." };
  }
  const publicIps = publicDns.addresses.map(normalizeIp);

  if (!deviceIps.length && !publicIps.length) {
    return { kind, verdict: Verdict.Warning, detail: `${host} doesn't exist or has no addresses.` };
  }
  if (!publicDns.exists && deviceIps.length) {
    return {
      kind,
      verdict: Verdict.Danger,
      detail: `Your network resolves ${host}, but public DNS says the domain doesn't exist.`,
    };
  }
  if (deviceIps.some(isReservedIp) && !publicIps.some(isReservedIp)) {
    return {
      kind,
      verdict: Verdict.Danger,
      detail: `Your network points ${host} to a private address (${deviceIps.find(isReservedIp)}) instead of its public one.`,
    };
  }
  if (publicIps.length && !deviceIps.some((ip) => publicIps.includes(ip))) {
    if (tls?.ok && tls.trusted) {
      return {
        kind,
        verdict: Verdict.Safe,
        detail: 'DNS answers differ (normal for CDNs), but the server proved its identity with a valid certificate.',
      };
    }
    return {
      kind,
      verdict: tls ? Verdict.Danger : Verdict.Warning,
      detail: tls
        ? "Your network and public DNS disagree on this domain's address, and the server can't prove its identity."
        : "Your network and public DNS disagree on this domain's address, and the link isn't encrypted to confirm it.",
    };
  }
  return { kind, verdict: Verdict.Safe, detail: 'Your network and public DNS agree on the address.' };
}

function ageInDays(date: Date) {
  return (Date.now() - date.getTime()) / DAY_MS;
}

function formatAge(days: number) {
  if (days < 1) return `${Math.max(1, Math.round(days * 24))} hours`;
  return `${Math.floor(days)} days`;
}

function checkDomainAge(domain: string, history: CertificateHistory, encrypted: boolean): LinkCheck {
  const kind = LinkCheckKind.DomainAge;
  if (!history) {
    return { kind, verdict: Verdict.Warning, detail: `Couldn't look up ${domain}'s certificate history.` };
  }
  if (!history.firstIssued) {
    // Every trusted certificate must be logged, so an HTTPS site with none is brand new.
    // An HTTP-only site may simply never have had one.
    return encrypted
      ? { kind, verdict: Verdict.Danger, detail: `${domain} has no certificates in public logs. It's likely brand new.` }
      : { kind, verdict: Verdict.Warning, detail: `${domain} has never had an HTTPS certificate, so its age can't be checked.` };
  }
  const age = ageInDays(history.firstIssued);
  return age < NEW_DOMAIN_DAYS
    ? {
        kind,
        verdict: Verdict.Danger,
        detail: `${domain}'s first certificate was issued only ${formatAge(age)} ago. New domains are often used for phishing.`,
      }
    : { kind, verdict: Verdict.Safe, detail: `${domain} has had certificates for at least ${formatAge(age)}.` };
}

function checkTls(host: string, apex: string, hostTls: TlsProbe, apexTls: TlsProbe | null): LinkCheck[] {
  if (!hostTls.ok) {
    return [
      {
        kind: LinkCheckKind.TlsConnection,
        verdict: Verdict.Warning,
        detail: `Couldn't open a secure connection to check the certificate (${hostTls.error}).`,
      },
    ];
  }
  const leaf = hostTls.leaf;
  const checks: LinkCheck[] = [];

  const matches = certificateMatchesHost(leaf, host);
  checks.push({
    kind: LinkCheckKind.CertificateHostname,
    verdict: matches ? Verdict.Safe : Verdict.Danger,
    detail: matches
      ? `Certificate is valid for ${host}.`
      : `Certificate is for ${leaf.dnsNames.slice(0, 3).join(', ') || leaf.subjectCommonName || 'another site'}, not ${host}.`,
  });

  const issuer = leaf.issuerOrg ?? leaf.issuerCommonName ?? 'Unknown issuer';
  const known = KNOWN_CERTIFICATE_ISSUERS.some((name) => issuer.toLowerCase().startsWith(name.toLowerCase()));
  checks.push({
    kind: LinkCheckKind.CertificateIssuer,
    verdict: !hostTls.trusted ? Verdict.Danger : known ? Verdict.Safe : Verdict.Warning,
    detail: !hostTls.trusted
      ? `Your device doesn't trust this certificate${hostTls.trustError ? `: ${hostTls.trustError}` : '.'}`
      : known
        ? `Issued by ${issuer}.`
        : `Issued by ${issuer}, which isn't a common certificate authority.`,
  });

  if (host !== apex && apexTls?.ok) {
    const sameCertificate = apexTls.leaf.der === leaf.der;
    const hostAge = ageInDays(leaf.notBefore);
    const fresh = !sameCertificate && hostAge < NEW_SUBDOMAIN_CERTIFICATE_DAYS;
    checks.push({
      kind: LinkCheckKind.SubdomainCertificate,
      verdict: fresh ? Verdict.Danger : Verdict.Safe,
      detail: sameCertificate
        ? `${host} uses the same certificate as ${apex}.`
        : fresh
          ? `${host} got its own certificate only ${formatAge(hostAge)} ago, on an otherwise established domain.`
          : `${host} has its own certificate, issued ${formatAge(hostAge)} ago.`,
    });
  }

  return checks;
}

async function runChecks(host: string, encrypted: boolean): Promise<LinkCheck[]> {
  const apex = getDomain(host) ?? host;
  const [hostTls, apexTls, history] = await Promise.all([
    encrypted ? probeTls(host) : null,
    encrypted && apex !== host ? probeTls(apex) : null,
    fetchCertificateHistory(apex),
  ]);
  const dns = await checkDns(host, hostTls);
  return [dns, checkDomainAge(apex, history, encrypted), ...(hostTls ? checkTls(host, apex, hostTls, apexTls) : [])];
}

/**
 * DNS and TLS checks for a link's host. Only performs a TLS handshake; never requests the URL itself.
 *
 * TODO(backend): these lookups run from the user's device, so the link's server sees the user's IP
 * and when they opened the email (per-recipient subdomains turn this into a read receipt), and
 * Cloudflare sees every looked-up hostname. Run them from our backend instead. See FUTURE_CHANGES.md.
 */
export function runNetworkChecks(host: string, encrypted: boolean): Promise<LinkCheck[]> {
  return cached(checksCache, `${encrypted ? 'https' : 'http'}://${host}`, () => runChecks(host, encrypted));
}
