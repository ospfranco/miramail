import { base64ToBytes, utf8Decode } from './bytes';
import { getAccessToken } from './google-auth';

const BASE = 'https://gmail.googleapis.com/gmail/v1/users/me';

async function gmailFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const request = async (token: string) =>
    fetch(`${BASE}${path}`, {
      ...init,
      headers: { ...init?.headers, Authorization: `Bearer ${token}` },
    });

  let res = await request(await getAccessToken());
  if (res.status === 401) res = await request(await getAccessToken(true));
  if (!res.ok) throw new Error(`Gmail API ${res.status}: ${await res.text()}`);
  return res.json();
}

export type MessageSummary = {
  id: string;
  threadId: string;
  from: string;
  subject: string;
  snippet: string;
  unread: boolean;
};

type MessagePart = {
  mimeType: string;
  filename?: string;
  headers: { name: string; value: string }[];
  body: { size: number; data?: string; attachmentId?: string };
  parts?: MessagePart[];
};

type RawMessage = {
  id: string;
  threadId: string;
  snippet: string;
  labelIds?: string[];
  payload: MessagePart;
};

function getHeader(part: MessagePart, name: string) {
  return part.headers.find((h) => h.name.toLowerCase() === name.toLowerCase())?.value ?? '';
}

export async function listInbox(maxResults = 20): Promise<MessageSummary[]> {
  const { messages = [] } = await gmailFetch<{ messages?: { id: string }[] }>(
    `/messages?labelIds=INBOX&maxResults=${maxResults}`,
  );

  const full = await Promise.all(
    messages.map((m) =>
      gmailFetch<RawMessage>(
        `/messages/${m.id}?format=metadata&metadataHeaders=From&metadataHeaders=Subject`,
      ),
    ),
  );

  return full.map((m) => ({
    id: m.id,
    threadId: m.threadId,
    from: getHeader(m.payload, 'From'),
    subject: getHeader(m.payload, 'Subject') || '(no subject)',
    snippet: m.snippet,
    unread: m.labelIds?.includes('UNREAD') ?? false,
  }));
}

export type MessageDetail = {
  id: string;
  from: string;
  /** Bare address from the From header, e.g. "noreply@github.com". */
  fromAddress: string;
  to: string;
  subject: string;
  date: string;
  body: string;
  bodyType: 'html' | 'text';
  /** Gmail's own SPF/DKIM/DMARC verdicts. Other servers' headers are dropped since senders can forge them. */
  authenticationResults: string[];
};

export async function getMessage(id: string): Promise<MessageDetail> {
  const m = await gmailFetch<RawMessage>(`/messages/${id}?format=full`);
  const html = findPart(m.payload, 'text/html');
  const text = html ? null : findPart(m.payload, 'text/plain');
  const part = html ?? text;

  const from = getHeader(m.payload, 'From');

  return {
    id: m.id,
    from,
    fromAddress: (/<([^>]+)>/.exec(from)?.[1] ?? from).trim().toLowerCase(),
    to: getHeader(m.payload, 'To'),
    subject: getHeader(m.payload, 'Subject') || '(no subject)',
    date: getHeader(m.payload, 'Date'),
    // Non-UTF-8 charsets (e.g. ISO-8859-1) will render garbled.
    body: part?.body.data ? utf8Decode(base64ToBytes(part.body.data)) : m.snippet,
    bodyType: html ? 'html' : 'text',
    authenticationResults: m.payload.headers
      .filter((h) => h.name.toLowerCase() === 'authentication-results')
      .map((h) => h.value)
      .filter((value) => /^\s*mx\.google\.com\s*;/i.test(value)),
  };
}

/** Whether the user has any other mail from or to `address` (spam and trash excluded). */
export async function hasCorrespondenceWith(address: string, excludeMessageId: string): Promise<boolean> {
  const query = encodeURIComponent(`{from:"${address}" to:"${address}"}`);
  const { messages = [] } = await gmailFetch<{ messages?: { id: string }[] }>(`/messages?q=${query}&maxResults=2`);
  return messages.some((m) => m.id !== excludeMessageId);
}

/** Depth-first search for the first inline (non-attachment) part of the given MIME type. */
function findPart(part: MessagePart, mimeType: string): MessagePart | null {
  if (part.mimeType === mimeType && !part.filename && part.body.data) return part;
  for (const child of part.parts ?? []) {
    const found = findPart(child, mimeType);
    if (found) return found;
  }
  return null;
}
