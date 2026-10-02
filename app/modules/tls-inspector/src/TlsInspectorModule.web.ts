import { registerWebModule, NativeModule } from 'expo';

// TlsInspectorModule is not available on the web platform.
class TlsInspectorModule extends NativeModule<{}> {}

export default registerWebModule(TlsInspectorModule, 'TlsInspectorModule');
