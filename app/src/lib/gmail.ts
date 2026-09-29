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
  to: string;
  subject: string;
  date: string;
  body: string;
  bodyType: 'html' | 'text';
};

export async function getMessage(id: string): Promise<MessageDetail> {
  const m = await gmailFetch<RawMessage>(`/messages/${id}?format=full`);
  const html = findPart(m.payload, 'text/html');
  const text = html ? null : findPart(m.payload, 'text/plain');
  const part = html ?? text;

  return {
    id: m.id,
    from: getHeader(m.payload, 'From'),
    to: getHeader(m.payload, 'To'),
    subject: getHeader(m.payload, 'Subject') || '(no subject)',
    date: getHeader(m.payload, 'Date'),
    body: part?.body.data ? decodeBase64Url(part.body.data) : m.snippet,
    bodyType: html ? 'html' : 'text',
  };
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

const BASE64_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';

// Gmail bodies are base64url-encoded UTF-8. Decoded by hand since React Native
// doesn't guarantee TextDecoder. Non-UTF-8 charsets (e.g. ISO-8859-1) will render garbled.
function decodeBase64Url(data: string): string {
  const bytes: number[] = [];
  let buffer = 0;
  let bits = 0;
  for (const char of data) {
    const value = BASE64_ALPHABET.indexOf(char === '+' ? '-' : char === '/' ? '_' : char);
    if (value === -1) continue; // padding / whitespace
    buffer = (buffer << 6) | value;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((buffer >> bits) & 0xff);
      buffer &= (1 << bits) - 1;
    }
  }

  let out = '';
  for (let i = 0; i < bytes.length; ) {
    const b = bytes[i++];
    let cp: number;
    if (b < 0x80) cp = b;
    else if (b >> 5 === 0x6) cp = ((b & 0x1f) << 6) | (bytes[i++] & 0x3f);
    else if (b >> 4 === 0xe) cp = ((b & 0x0f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f);
    else
      cp =
        ((b & 0x07) << 18) |
        ((bytes[i++] & 0x3f) << 12) |
        ((bytes[i++] & 0x3f) << 6) |
        (bytes[i++] & 0x3f);
    out += String.fromCodePoint(cp);
  }
  return out;
}
