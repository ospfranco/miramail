import { base64ToBytes, utf8Decode } from './bytes';

// Minimal DER/X.509 reader: just the fields the link checks need.
// Full ASN.1 libraries rely on WebCrypto, which React Native doesn't provide.

export type Certificate = {
  /** Base64 DER, used to tell whether two hosts serve the same certificate. */
  der: string;
  notBefore: Date;
  notAfter: Date;
  issuerOrg: string | null;
  issuerCommonName: string | null;
  subjectCommonName: string | null;
  dnsNames: string[];
  ipAddresses: string[];
};

enum Tag {
  Utf8String = 0x0c,
  UtcTime = 0x17,
  BmpString = 0x1e,
  Version = 0xa0,
  Extensions = 0xa3,
  SanDnsName = 0x82,
  SanIpAddress = 0x87,
}

enum Oid {
  CommonName = '550403',
  Organization = '55040a',
  SubjectAltName = '551d11',
}

type Node = { tag: number; start: number; end: number };

function readNode(bytes: Uint8Array, offset: number): Node {
  const tag = bytes[offset];
  let length = bytes[offset + 1];
  let start = offset + 2;
  if (length & 0x80) {
    const count = length & 0x7f;
    length = 0;
    for (let i = 0; i < count; i++) length = length * 256 + bytes[start++];
  }
  return { tag, start, end: start + length };
}

function children(bytes: Uint8Array, node: Node): Node[] {
  const out: Node[] = [];
  for (let offset = node.start; offset < node.end; ) {
    const child = readNode(bytes, offset);
    out.push(child);
    offset = child.end;
  }
  return out;
}

function hex(bytes: Uint8Array, node: Node) {
  let out = '';
  for (let i = node.start; i < node.end; i++) out += bytes[i].toString(16).padStart(2, '0');
  return out;
}

function latin1(bytes: Uint8Array, node: Node) {
  return String.fromCharCode(...bytes.subarray(node.start, node.end));
}

function readString(bytes: Uint8Array, node: Node): string {
  const content = bytes.subarray(node.start, node.end);
  if (node.tag === Tag.Utf8String) return utf8Decode(content);
  if (node.tag === Tag.BmpString) {
    let out = '';
    for (let i = 0; i + 1 < content.length; i += 2) out += String.fromCharCode((content[i] << 8) | content[i + 1]);
    return out;
  }
  return latin1(bytes, node);
}

function readTime(bytes: Uint8Array, node: Node): Date {
  const s = latin1(bytes, node);
  const [year, rest] =
    node.tag === Tag.UtcTime
      ? [Number(s.slice(0, 2)) + (Number(s.slice(0, 2)) < 50 ? 2000 : 1900), s.slice(2)]
      : [Number(s.slice(0, 4)), s.slice(4)];
  const [month, day, hour, minute, second] = [0, 2, 4, 6, 8].map((i) => Number(rest.slice(i, i + 2) || 0));
  return new Date(Date.UTC(year, month - 1, day, hour, minute, second));
}

/** Reads the given attributes (by OID) from an X.500 Name. */
function readName(bytes: Uint8Array, name: Node) {
  const attributes = new Map<string, string>();
  for (const set of children(bytes, name)) {
    for (const pair of children(bytes, set)) {
      const [oid, value] = children(bytes, pair);
      if (oid && value && !attributes.has(hex(bytes, oid))) attributes.set(hex(bytes, oid), readString(bytes, value));
    }
  }
  return attributes;
}

function formatIp(octets: Uint8Array) {
  if (octets.length === 4) return Array.from(octets).join('.');
  const groups: string[] = [];
  for (let i = 0; i + 1 < octets.length; i += 2) groups.push(((octets[i] << 8) | octets[i + 1]).toString(16));
  return groups.join(':');
}

export function parseCertificate(der: string): Certificate {
  const bytes = base64ToBytes(der);
  const certificate = readNode(bytes, 0);
  const tbs = children(bytes, certificate)[0];
  const fields = children(bytes, tbs);
  // Skip the optional explicit version, then: serial, signature algorithm, issuer, validity, subject.
  const base = fields[0].tag === Tag.Version ? 1 : 0;
  const issuer = readName(bytes, fields[base + 2]);
  const [notBefore, notAfter] = children(bytes, fields[base + 3]).map((t) => readTime(bytes, t));
  const subject = readName(bytes, fields[base + 4]);

  const dnsNames: string[] = [];
  const ipAddresses: string[] = [];
  const extensions = fields.find((f) => f.tag === Tag.Extensions);
  if (extensions) {
    const [list] = children(bytes, extensions);
    for (const extension of children(bytes, list)) {
      const parts = children(bytes, extension);
      if (hex(bytes, parts[0]) !== Oid.SubjectAltName) continue;
      const value = parts[parts.length - 1]; // OCTET STRING, after the optional "critical" flag
      const [generalNames] = children(bytes, value);
      for (const name of children(bytes, generalNames)) {
        if (name.tag === Tag.SanDnsName) dnsNames.push(latin1(bytes, name).toLowerCase());
        if (name.tag === Tag.SanIpAddress) ipAddresses.push(formatIp(bytes.subarray(name.start, name.end)));
      }
    }
  }

  return {
    der,
    notBefore,
    notAfter,
    issuerOrg: issuer.get(Oid.Organization) ?? null,
    issuerCommonName: issuer.get(Oid.CommonName) ?? null,
    subjectCommonName: subject.get(Oid.CommonName) ?? null,
    dnsNames,
    ipAddresses,
  };
}

/** RFC 6125 matching: a wildcard covers exactly one leftmost label. */
export function certificateMatchesHost(cert: Certificate, host: string): boolean {
  const target = host.toLowerCase().replace(/\.$/, '');
  if (cert.ipAddresses.includes(target)) return true;
  // Legacy fallback: the subject CN only counts when there are no SAN entries.
  const names = cert.dnsNames.length ? cert.dnsNames : cert.subjectCommonName ? [cert.subjectCommonName.toLowerCase()] : [];
  return names.some((name) => {
    if (name === target) return true;
    if (!name.startsWith('*.')) return false;
    const dot = target.indexOf('.');
    return dot > 0 && target.slice(dot + 1) === name.slice(2);
  });
}
