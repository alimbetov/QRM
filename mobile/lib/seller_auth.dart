import 'package:flutter_appauth/flutter_appauth.dart';
import 'buyer_auth.dart';

/// Independent mobile OIDC public client for seller roles.
class SellerAuth implements BuyerAuthGateway {
  static const issuer=String.fromEnvironment('ZAKAZ_OIDC_ISSUER');
  static const clientId=String.fromEnvironment('ZAKAZ_SELLER_OIDC_CLIENT_ID');
  static const redirectUrl=String.fromEnvironment('ZAKAZ_SELLER_OIDC_REDIRECT_URI');
  static const logoutRedirect=String.fromEnvironment(
    'ZAKAZ_SELLER_OIDC_LOGOUT_REDIRECT_URI',
    defaultValue:'kz.zakaz.mobile:/seller-logout');

  static bool get configured=>issuer.startsWith('https://') &&
    clientId.isNotEmpty && redirectUrl.startsWith('kz.zakaz.mobile:/') &&
    logoutRedirect.startsWith('kz.zakaz.mobile:/');

  @override bool get isConfigured=>configured;
  final FlutterAppAuth appAuth=FlutterAppAuth();

  @override Future<BuyerSession> signIn() async {
    if(!configured)throw StateError('Seller OIDC not configured');
    final result=await appAuth.authorizeAndExchangeCode(
      AuthorizationTokenRequest(clientId,redirectUrl,issuer:issuer,
        scopes:['openid','profile','email','deal:bid','order:confirm','leads:read']),
    );
    final token=result.accessToken,identity=result.idToken;
    if(token==null||token.isEmpty||identity==null||identity.isEmpty)
      throw StateError('OIDC seller tokens not issued');
    return BuyerSession(accessToken:token,idToken:identity);
  }

  @override Future<void> signOut(BuyerSession session) async {
    if(!configured)throw StateError('Seller OIDC not configured');
    await appAuth.endSession(EndSessionRequest(
      issuer:issuer,idTokenHint:session.idToken,
      postLogoutRedirectUrl:logoutRedirect));
  }
}
