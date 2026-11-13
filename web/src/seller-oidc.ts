import {UserManager,WebStorageStateStore} from 'oidc-client-ts';
import {safeReturnPath} from './oidc';

const authority=(import.meta.env.VITE_SELLER_OIDC_AUTHORITY || '').trim();
const client=(import.meta.env.VITE_SELLER_OIDC_CLIENT_ID || '').trim();

/** Independent SPA client, never shares buyer tokens or storage identity. */
export const sellerAuth=authority && client ? new UserManager({
  authority,client_id:client,
  redirect_uri:window.location.origin+'/seller/auth/callback',
  post_logout_redirect_uri:window.location.origin+'/seller/auth/signed-out',
  response_type:'code',
  scope:'openid profile email catalog:write deal:bid order:confirm leads:read',
  loadUserInfo:false,
  automaticSilentRenew:false,
  userStore:new WebStorageStateStore({store:window.sessionStorage})
}) : null;

export function sellerReturnTo(value:unknown):string {
  const path=safeReturnPath(value);
  return path==='/seller' || path.startsWith('/seller?') ? path : '/seller';
}
export async function loginSeller():Promise<void> {
  if(!sellerAuth)throw new Error('Seller OIDC not configured');
  await sellerAuth.signinRedirect({state:{returnTo:'/seller'}});
}
export async function logoutSeller():Promise<void> {
  if(!sellerAuth)throw new Error('Seller OIDC not configured');
  await sellerAuth.signoutRedirect();
}
