import { getDomain } from 'tldts';

import { hasCorrespondenceWith, type MessageDetail } from './gmail';
import type { SecurityCheck } from './security';
import { Verdict } from './verdict';

/** Result values from RFC 8601 Authentication-Results. */
enum AuthResult {
  Pass = 'pass',
  Fail = 'fail',
  SoftFail = 'softfail',
  Neutral = 'neutral',
  None = 'none',
  TempError = 'temperror',
  PermError = 'permerror',
}

enum AuthMethod {
  Dmarc = 'dmarc',
  Dkim = 'dkim',
  Spf = 'spf',
}

type MethodResult = { method: string; result: string; properties: Map<string, string> };

/**
 * Parses Gmail's Authentication-Results header, e.g.
 * `mx.google.com; dkim=pass header.i=@github.com; spf=pass (...) smtp.mailfrom=x@github.com; dmarc=pass header.from=github.com`
 */
function parseAuthenticationResults(header: string): MethodResult[] {
  let text = header;
  // Drop (comments), innermost first so nested parentheses are handled.
  while (/\([^()]*\)/.test(text)) text = text.replace(/\([^()]*\)/g, ' ');
  const [, ...statements] = text.split(';'); // first segment is the authserv-id

  return statements.flatMap((statement) => {
    const [head, ...props] = statement.trim().split(/\s+/);
    const match = /^([a-z0-9-]+)=([a-z]+)$/i.exec(head ?? '');
    if (!match) return [];
    const properties = new Map<string, string>();
    for (const prop of props) {
      const eq = prop.indexOf('=');
      if (eq > 0) properties.set(prop.slice(0, eq).toLowerCase(), prop.slice(eq + 1).toLowerCase());
    }
    return [{ method: match[1].toLowerCase(), result: match[2].toLowerCase(), properties }];
  });
}

function domainOf(addressOrDomain: string | undefined) {
  if (!addressOrDomain) return null;
  return addressOrDomain.slice(addressOrDomain.lastIndexOf('@') + 1).replace(/\.$/, '') || null;
}

/** DMARC "relaxed" alignment: same organizational domain (e.g. mail.github.com aligns with github.com). */
function aligned(a: string | null, b: string) {
  return !!a && (getDomain(a) ?? a) === (getDomain(b) ?? b);
}

function checkSenderDomain(message: MessageDetail): SecurityCheck {
  const fromDomain = domainOf(message.fromAddress) ?? 'unknown';
  const results = message.authenticationResults.flatMap(parseAuthenticationResults);

  if (!results.length) {
    return {
      label: 'Sender not verified',
      detail: `Gmail recorded no authentication results, so ${fromDomain} can't be confirmed as the real sender.`,
      verdict: Verdict.Warning,
    };
  }

  const dmarc = results.find((r) => r.method === AuthMethod.Dmarc);
  if (dmarc?.result === AuthResult.Pass) {
    return {
      label: 'Sender verified',
      detail: `Gmail confirmed this email really comes from ${fromDomain} (DMARC).`,
      verdict: Verdict.Safe,
    };
  }
  if (dmarc?.result === AuthResult.Fail) {
    return {
      label: 'Sender verification failed',
      detail: `${fromDomain} says it didn't send this email (DMARC failed). It may be forged.`,
      verdict: Verdict.Danger,
    };
  }

  // No conclusive DMARC (the domain has no policy, or a lookup error): judge SPF/DKIM directly.
  const dkimPasses = results.filter((r) => r.method === AuthMethod.Dkim && r.result === AuthResult.Pass);
  const spfPasses = results.filter((r) => r.method === AuthMethod.Spf && r.result === AuthResult.Pass);
  const alignedDkim = dkimPasses.find((r) => aligned(domainOf(r.properties.get('header.d') ?? r.properties.get('header.i')), fromDomain));
  const alignedSpf = spfPasses.find((r) => aligned(domainOf(r.properties.get('smtp.mailfrom')), fromDomain));

  if (alignedDkim || alignedSpf) {
    return {
      label: 'Sender verified',
      detail: `Gmail confirmed this email was ${alignedDkim ? 'signed' : 'sent'} by ${fromDomain} (${alignedDkim ? 'DKIM' : 'SPF'}).`,
      verdict: Verdict.Safe,
    };
  }

  const failed = results.some(
    (r) => (r.method === AuthMethod.Dkim || r.method === AuthMethod.Spf) && r.result === AuthResult.Fail,
  );
  if (failed) {
    return {
      label: 'Sender verification failed',
      detail: `This email failed ${fromDomain}'s sender checks. It may be forged.`,
      verdict: Verdict.Danger,
    };
  }

  const relay = domainOf(
    dkimPasses[0]?.properties.get('header.d') ?? dkimPasses[0]?.properties.get('header.i') ?? spfPasses[0]?.properties.get('smtp.mailfrom'),
  );
  return {
    label: 'Sender not verified',
    detail: relay
      ? `Sent through ${relay} on behalf of ${fromDomain}, but ${fromDomain} itself isn't verified.`
      : `${fromDomain} couldn't be verified as the real sender.`,
    verdict: Verdict.Warning,
  };
}

async function checkSenderHistory(message: MessageDetail): Promise<SecurityCheck> {
  try {
    const known = await hasCorrespondenceWith(message.fromAddress, message.id);
    return known
      ? { label: 'Known sender', detail: "You've exchanged email with this address before.", verdict: Verdict.Safe }
      : {
          label: 'First message from this sender',
          detail: "You haven't exchanged email with this address before.",
          verdict: Verdict.Warning,
        };
  } catch {
    return { label: 'Sender history unknown', detail: "Couldn't search your past email.", verdict: Verdict.Warning };
  }
}

export async function checkSender(message: MessageDetail): Promise<SecurityCheck[]> {
  return [checkSenderDomain(message), await checkSenderHistory(message)];
}
