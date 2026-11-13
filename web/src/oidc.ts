import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

const authority=(import.meta.env.VITE_OIDC_AUTHORITY || '').trim();
const clientId=(import.meta.env.VITE_OIDC_CLIENT_ID || '').trim();

/** No browser login without a real configured OIDC public-client registration. */
export const buyerAuth = authority && clientId ? new UserManager({
  authority,
  client_id: clientId,
  redirect_uri: window.location.origin + '/auth/callback',
  response_type: 'code',
  scope: 'openid profile deal:write order:read',
  loadUserInfo: false,
  automaticSilentRenew: false,
  userStore: new WebStorageStateStore({ store: window.sessionStorage })
}) : null;

export async function loginBuyer(returnTo: string): Promise<void> {
  if (!buyerAuth) throw new Error('OIDC is not configured');
  await buyerAuth.signinRedirect({ state: { returnTo } });
}
export async function clearBuyerSession(): Promise<void> {
  await buyerAuth?.removeUser();
}
export function safeReturnPath(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') ||
      value.includes('\\') || value.includes('\n') || value.includes('\r')) return '/';
  return value;
}
