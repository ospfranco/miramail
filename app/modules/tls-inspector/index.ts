// Re-export the native module. On web, it will be resolved to TlsInspectorModule.web.ts
// and on native platforms to TlsInspectorModule.ts
export { default } from './src/TlsInspectorModule';
export * from './src/TlsInspector.types';
