import { runNetworkChecks } from './link-checks';
import { LinkCheckKind, Verdict, worstVerdict, type LinkCheck } from './verdict';

export { LinkCheckLabel, Verdict, VerdictColor, VerdictGlyph, type LinkCheck } from './verdict';

export type FoundLink = { url: string; text: string };

export type ScannedLink = FoundLink & {
  host: string;
  verdict: Verdict;
  checks: LinkCheck[];
};

export type SecurityCheck = { label: string; detail: string; verdict: Verdict };

export type SecurityEvaluation = {
  verdict: Verdict;
  summary: string;
  checks: SecurityCheck[];
};

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

function isIpAddress(host: string) {
  return /^\d{1,3}(\.\d{1,3}){3}$/.test(host) || host.startsWith('[');
}

/** Checks that only look at the URL and its visible text. */
function localChecks(link: FoundLink, scheme: string, host: string, hasUserInfo: boolean): LinkCheck[] {
  const checks: LinkCheck[] = [];
  const add = (kind: LinkCheckKind, verdict: Verdict, detail: string) => checks.push({ kind, verdict, detail });

  if (isIpAddress(host)) add(LinkCheckKind.LinkFormat, Verdict.Danger, 'Points to a raw IP address instead of a domain.');
  if (hasUserInfo) add(LinkCheckKind.LinkFormat, Verdict.Danger, 'Hides the real destination behind an "@".');
  const shownHost = hostInText(link.text);
  if (shownHost && !sameSite(shownHost, host)) {
    add(LinkCheckKind.DisplayText, Verdict.Danger, `Text shows ${shownHost} but the link goes to ${host}.`);
  }
  if (scheme === 'http') add(LinkCheckKind.Encryption, Verdict.Warning, 'Not encrypted (http).');
  if (host.split('.').some((label) => label.startsWith('xn--'))) {
    add(LinkCheckKind.LookalikeDomain, Verdict.Warning, 'Uses look-alike (punycode) characters in the domain.');
  }
  if (URL_SHORTENERS.includes(host)) {
    add(LinkCheckKind.Shortener, Verdict.Warning, 'Shortened link hides the final destination.');
  }
  return checks;
}

async function scanLink(link: FoundLink): Promise<ScannedLink> {
  const result = (host: string, checks: LinkCheck[]): ScannedLink => ({
    ...link,
    host,
    checks,
    verdict: worstVerdict(checks.map((c) => c.verdict)),
  });

  const parsed = parseUrl(link.url);
  if (!parsed) {
    return result('', [{ kind: LinkCheckKind.LinkFormat, verdict: Verdict.Warning, detail: 'Link could not be parsed.' }]);
  }
  const { scheme, host, hasUserInfo } = parsed;

  if (scheme === 'mailto' || scheme === 'tel') return result(host, []);
  if (scheme !== 'http' && scheme !== 'https') {
    return result(host, [
      { kind: LinkCheckKind.LinkFormat, verdict: Verdict.Danger, detail: `Uses a "${scheme}:" link, which can run code.` },
    ]);
  }

  const checks = localChecks(link, scheme, host, hasUserInfo);
  if (host && !isIpAddress(host)) checks.push(...(await runNetworkChecks(host, scheme === 'https')));
  return result(host, checks);
}

export async function scanLinks(links: FoundLink[]): Promise<ScannedLink[]> {
  const unique = new Map<string, FoundLink>();
  for (const link of links) {
    // Keep the entry with visible text, since that's what the mismatch check needs.
    if (!unique.has(link.url) || (!unique.get(link.url)!.text && link.text)) unique.set(link.url, link);
  }
  return Promise.all([...unique.values()].map(scanLink));
}

export function evaluateEmail(senderChecks: SecurityCheck[], links: ScannedLink[]): SecurityEvaluation {
  const bad = links.filter((l) => l.verdict === Verdict.Danger).length;
  const suspicious = links.filter((l) => l.verdict === Verdict.Warning).length;
  const linkCheck: SecurityCheck = {
    label: links.length === 0 ? 'No links' : `${links.length} link${links.length === 1 ? '' : 's'} scanned`,
    detail:
      bad || suspicious
        ? [bad && `${bad} unsafe`, suspicious && `${suspicious} suspicious`].filter(Boolean).join(', ')
        : 'No problems found in links.',
    verdict: bad ? Verdict.Danger : suspicious ? Verdict.Warning : Verdict.Safe,
  };

  const checks = [...senderChecks, linkCheck];
  const verdict = worstVerdict(checks.map((c) => c.verdict));
  const summary = {
    [Verdict.Safe]: 'No problems found',
    [Verdict.Warning]: 'Be careful with this email',
    [Verdict.Danger]: 'This email looks dangerous',
  }[verdict];

  return { verdict, summary, checks };
}
