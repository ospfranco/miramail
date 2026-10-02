export enum Verdict {
  Safe = 'safe',
  Warning = 'warning',
  Danger = 'danger',
}

export const VerdictGlyph: Record<Verdict, string> = {
  [Verdict.Safe]: '✓',
  [Verdict.Warning]: '!',
  [Verdict.Danger]: '✕',
};

export const VerdictColor: Record<Verdict, string> = {
  [Verdict.Safe]: '#30A46C',
  [Verdict.Warning]: '#E5A000',
  [Verdict.Danger]: '#E5484D',
};

const SEVERITY: Record<Verdict, number> = {
  [Verdict.Safe]: 0,
  [Verdict.Warning]: 1,
  [Verdict.Danger]: 2,
};

export function worstVerdict(verdicts: Verdict[]): Verdict {
  return verdicts.reduce<Verdict>((a, b) => (SEVERITY[b] > SEVERITY[a] ? b : a), Verdict.Safe);
}

export enum LinkCheckKind {
  // Local checks on the URL itself
  LinkFormat = 'link-format',
  DisplayText = 'display-text',
  Encryption = 'encryption',
  LookalikeDomain = 'lookalike-domain',
  Shortener = 'shortener',
  // Network checks
  DnsSpoofing = 'dns-spoofing',
  TlsConnection = 'tls-connection',
  DomainAge = 'domain-age',
  SubdomainCertificate = 'subdomain-certificate',
  CertificateHostname = 'certificate-hostname',
  CertificateIssuer = 'certificate-issuer',
}

export const LinkCheckLabel: Record<LinkCheckKind, string> = {
  [LinkCheckKind.LinkFormat]: 'Link format',
  [LinkCheckKind.DisplayText]: 'Link text',
  [LinkCheckKind.Encryption]: 'Encryption',
  [LinkCheckKind.LookalikeDomain]: 'Look-alike domain',
  [LinkCheckKind.Shortener]: 'Link shortener',
  [LinkCheckKind.DnsSpoofing]: 'DNS',
  [LinkCheckKind.TlsConnection]: 'TLS connection',
  [LinkCheckKind.DomainAge]: 'Domain age',
  [LinkCheckKind.SubdomainCertificate]: 'Subdomain certificate',
  [LinkCheckKind.CertificateHostname]: 'Certificate hostname',
  [LinkCheckKind.CertificateIssuer]: 'Certificate issuer',
};

export type LinkCheck = { kind: LinkCheckKind; verdict: Verdict; detail: string };
