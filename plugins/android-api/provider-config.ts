/** pi-ai owns the API protocol; accept common copied endpoint URLs at its boundary. */
export function providerBaseURL(type: string, input: string): string {
  const url = new URL(input.trim());
  let path = url.pathname.replace(/\/+$/, '');
  if (type === 'anthropic') {
    path = path.replace(/\/messages$/, '').replace(/\/v1$/, '');
  } else {
    path = path.replace(/\/chat\/completions$/, '');
    if (!path) path = '/v1';
  }
  url.pathname = path || '/';
  return url.toString().replace(/\/$/, '');
}
