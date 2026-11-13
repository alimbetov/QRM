import 'package:flutter_appauth/flutter_appauth.dart';

/// In-memory OIDC session. Access token, ID token and logout state are never
/// persisted to disk or placed in URL query parameters.
class BuyerSession {
  final String accessToken;
  final String idToken;
  const BuyerSession({required this.accessToken, required this.idToken});
}

/// Public AppAuth client uses native Authorization Code + PKCE only.
/// The IdP handles account registration/email verification and RP logout.
class BuyerAuth {
  static const issuer=String.fromEnvironment('ZAKAZ_OIDC_ISSUER');
  static const clientId=String.fromEnvironment('ZAKAZ_OIDC_CLIENT_ID');
  static const redirectUrl=String.fromEnvironment('ZAKAZ_OIDC_REDIRECT_URI');
  static const logoutRedirectUrl=String.fromEnvironment(
      'ZAKAZ_OIDC_LOGOUT_REDIRECT_URI',defaultValue:'kz.zakaz.mobile:/logout');

  static bool get configured => issuer.startsWith('https://') &&
      clientId.isNotEmpty && redirectUrl.isNotEmpty &&
      logoutRedirectUrl.startsWith('kz.zakaz.mobile:/');

  final FlutterAppAuth appAuth=FlutterAppAuth();

  Future<BuyerSession> signIn() async {
    if (!configured) throw StateError('Real OIDC provider is not configured');
    final tokens=await appAuth.authorizeAndExchangeCode(
      AuthorizationTokenRequest(
        clientId,redirectUrl,issuer:issuer,
        scopes:['openid','profile','email','deal:write','order:read'],
      ),
    );
    final accessToken=tokens.accessToken;
    final idToken=tokens.idToken;
    if(accessToken==null || accessToken.isEmpty || idToken==null || idToken.isEmpty){
      throw StateError('Provider did not issue OIDC access and ID tokens');
    }
    return BuyerSession(accessToken:accessToken,idToken:idToken);
  }

  /// EndSession triggers the provider-managed browser session logout and
  /// validates its native redirect. The caller clears local state ONLY on success.
  Future<void> signOut(BuyerSession session) async {
    if (!configured) throw StateError('Real OIDC provider is not configured');
    await appAuth.endSession(EndSessionRequest(
      issuer:issuer,
      idTokenHint:session.idToken,
      postLogoutRedirectUrl:logoutRedirectUrl,
    ));
  }
}
