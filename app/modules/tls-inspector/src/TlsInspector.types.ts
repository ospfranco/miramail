export type TlsInspection = {
  /** Certificate chain as presented by the server, leaf first, base64-encoded DER. */
  chain: string[];
  /** Whether the platform trusts the chain for this host (CA trust + hostname). */
  trusted: boolean;
  trustError?: string | null;
};
