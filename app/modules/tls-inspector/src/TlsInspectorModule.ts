import { NativeModule, requireNativeModule } from 'expo';

import type { TlsInspection } from './TlsInspector.types';

declare class TlsInspectorModule extends NativeModule<{}> {
  /** TLS handshake only; rejects if the host can't be reached. No HTTP request is sent. */
  inspectTls(host: string, port: number, timeoutMs: number): Promise<TlsInspection>;
  /** System resolver lookup. Returns an empty array if the name doesn't resolve. */
  resolveHost(host: string): Promise<string[]>;
}

export default requireNativeModule<TlsInspectorModule>('TlsInspector');
