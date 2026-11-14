import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

const authority=(import.meta.env.VITE_OIDC_AUTHORITY || '').trim();
const clientId=(import.meta.env.VITE_OIDC_CLIENT_ID || '').trim();

/** Public SPA: Authorization Code + S256 PKCE. No password grant, no client secret. */
export const buyerAuth = authority && clientId ? new UserManager({
  authority,
  client_id: clientId,
  redirect_uri: window.location.origin + '/auth/callback',
  post_logout_redirect_uri: window.location.origin + '/auth/signed-out',
  response_type: 'code',
  scope: 'openid profile email deal:write order:read',
  loadUserInfo: false,
  automaticSilentRenew: false,
  userStore: new WebStorageStateStore({ store: window.sessionStorage })
}) : null;

export async function loginBuyer(returnTo: string): Promise<void> {
  if (!buyerAuth) throw new Error('OIDC is not configured');
  await buyerAuth.signinRedirect({ state: { returnTo: safeReturnPath(returnTo) } });
}

/** OIDC prompt=create goes through the registered Keycloak flow; no direct auth-page URLs. */
export async function registerBuyer(returnTo: string): Promise<void> {
  if (!buyerAuth) throw new Error('OIDC is not configured');
  await buyerAuth.signinRedirect({
    state: { returnTo: safeReturnPath(returnTo) },
    extraQueryParams: { prompt: 'create' }
  });
}

/** RP-initiated logout. Do not claim server-side logout if Keycloak redirect fails. */
export async function logoutBuyer(): Promise<void> {
  if (!buyerAuth) throw new Error('OIDC is not configured');
  await buyerAuth.signoutRedirect();
}

/** Only the verified OIDC end-session callback can finalize local logout. */
export async function completeBuyerLogout(): Promise<void> {
  if (!buyerAuth) throw new Error('OIDC is not configured');
  await buyerAuth.signoutRedirectCallback();
  await buyerAuth.removeUser();
}

export function safeReturnPath(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') ||
      value.includes('\\') || value.includes('\n') || value.includes('\r')) return '/';
  return value;
}
