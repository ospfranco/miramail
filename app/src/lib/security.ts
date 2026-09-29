import type { MessageDetail } from './gmail';

export type Verdict = 'safe' | 'warning' | 'danger';

export type FoundLink = { url: string; text: string };

export type ScannedLink = FoundLink & {
  host: string;
  verdict: Verdict;
  reasons: string[];
};

export type SecurityCheck = { label: string; detail: string; verdict: Verdict };

export type SecurityEvaluation = {
  verdict: Verdict;
  summary: string;
  checks: SecurityCheck[];
};

export const VerdictGlyph: Record<Verdict, string> = { safe: '✓', warning: '!', danger: '✕' };
export const VerdictColor: Record<Verdict, string> = {
  safe: '#30A46C',
  warning: '#E5A000',
  danger: '#E5484D',
};

const SEVERITY: Record<Verdict, number> = { safe: 0, warning: 1, danger: 2 };

function worst(verdicts: Verdict[]): Verdict {
  return verdicts.reduce<Verdict>((a, b) => (SEVERITY[b] > SEVERITY[a] ? b : a), 'safe');
}

const URL_SHORTENERS = ['bit.ly', 'tinyurl.com', 't.co', 'goo.gl', 'ow.ly', 'is.gd', 'buff.ly', 'rebrand.ly', 'cutt.ly'];

// RN's URL polyfill doesn't implement `hostname`, so parse the parts we need by hand.
function parseUrl(url: string) {
  const match = /^([a-z][a-z0-9+.-]*):(?:\/\/([^/?#]*))?/i.exec(url.trim());
  if (!match) return null;
  const authority = match[2] ?? '';
  const at = authority.lastIndexOf('@');
  const host = (at === -1 ? authority : authority.slice(at + 1)).replace(/:\d+$/, '').toLowerCase();
  return { scheme: match[1].toLowerCase(), host, hasUserInfo: at !== -1 };
}

/** Pulls a hostname out of link text that looks like a URL or bare domain, e.g. "paypal.com/login". */
function hostInText(text: string): string | null {
  const match = /^\s*(?:https?:\/\/)?(?:www\.)?([a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,})(?:[/:?#]\S*)?\s*$/i.exec(text);
  return match ? match[1].toLowerCase() : null;
}

function sameSite(a: string, b: string) {
  const strip = (h: string) => h.replace(/^www\./, '');
  const x = strip(a);
  const y = strip(b);
  return x === y || x.endsWith(`.${y}`) || y.endsWith(`.${x}`);
}

// Local heuristics only, standing in for a real URL reputation service.
function scanLink(link: FoundLink): ScannedLink {
  const parsed = parseUrl(link.url);
  if (!parsed) {
    return { ...link, host: '', verdict: 'warning', reasons: ['Link could not be parsed'] };
  }
  const { scheme, host, hasUserInfo } = parsed;

  if (scheme === 'mailto' || scheme === 'tel') {
    return { ...link, host, verdict: 'safe', reasons: [] };
  }
  if (scheme !== 'http' && scheme !== 'https') {
    return { ...link, host, verdict: 'danger', reasons: [`Uses a "${scheme}:" link, which can run code`] };
  }

  const danger: string[] = [];
  const warning: string[] = [];

  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host) || host.startsWith('[')) {
    danger.push('Points to a raw IP address instead of a domain');
  }
  if (hasUserInfo) danger.push('Hides the real destination behind an "@"');
  const shownHost = hostInText(link.text);
  if (shownHost && !sameSite(shownHost, host)) {
    danger.push(`Text shows ${shownHost} but the link goes to ${host}`);
  }
  if (scheme === 'http') warning.push('Not encrypted (http)');
  if (host.split('.').some((label) => label.startsWith('xn--'))) {
    warning.push('Uses look-alike (punycode) characters in the domain');
  }
  if (URL_SHORTENERS.includes(host)) warning.push('Shortened link hides the final destination');

  const verdict: Verdict = danger.length ? 'danger' : warning.length ? 'warning' : 'safe';
  return { ...link, host, verdict, reasons: [...danger, ...warning] };
}

export async function scanLinks(links: FoundLink[]): Promise<ScannedLink[]> {
  const unique = new Map<string, FoundLink>();
  for (const link of links) {
    // Keep the entry with visible text, since that's what the mismatch check needs.
    if (!unique.has(link.url) || (!unique.get(link.url)!.text && link.text)) unique.set(link.url, link);
  }
  return [...unique.values()].map(scanLink);
}

export function evaluateEmail(message: MessageDetail, links: ScannedLink[]): SecurityEvaluation {
  const senderDomain = /@([^>\s]+)/.exec(message.from)?.[1]?.toLowerCase() ?? 'unknown';

  // SAMPLE DATA: placeholder sender checks until real SPF/DKIM/DMARC verification is wired up.
  const senderChecks: SecurityCheck[] = [
    {
      label: 'Sender domain not verified',
      detail: `${senderDomain} could not be verified as the real sender.`,
      verdict: 'warning',
    },
    {
      label: 'First message from this sender',
      detail: "You haven't received email from this address before.",
      verdict: 'warning',
    },
  ];

  const bad = links.filter((l) => l.verdict === 'danger').length;
  const suspicious = links.filter((l) => l.verdict === 'warning').length;
  const linkCheck: SecurityCheck = {
    label: links.length === 0 ? 'No links' : `${links.length} link${links.length === 1 ? '' : 's'} scanned`,
    detail:
      bad || suspicious
        ? [bad && `${bad} unsafe`, suspicious && `${suspicious} suspicious`].filter(Boolean).join(', ')
        : 'No problems found in links.',
    verdict: bad ? 'danger' : suspicious ? 'warning' : 'safe',
  };

  const checks = [...senderChecks, linkCheck];
  const verdict = worst(checks.map((c) => c.verdict));
  const summary = {
    safe: 'No problems found',
    warning: 'Be careful with this email',
    danger: 'This email looks dangerous',
  }[verdict];

  return { verdict, summary, checks };
}
