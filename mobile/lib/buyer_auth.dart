import 'package:flutter_appauth/flutter_appauth.dart';

/// External OIDC public client must be registered with matching app redirect URI.
/// Access token is returned to the current screen only and is not written to disk.
class BuyerAuth {
  static const issuer=String.fromEnvironment('ZAKAZ_OIDC_ISSUER');
  static const clientId=String.fromEnvironment('ZAKAZ_OIDC_CLIENT_ID');
  static const redirectUrl=String.fromEnvironment('ZAKAZ_OIDC_REDIRECT_URI');
  static bool get configured => issuer.startsWith('https://') &&
      clientId.isNotEmpty && redirectUrl.isNotEmpty;

  final FlutterAppAuth appAuth=FlutterAppAuth();
  Future<String> signIn() async {
    if (!configured) throw StateError('Real OIDC provider is not configured');
    final tokens=await appAuth.authorizeAndExchangeCode(
      AuthorizationTokenRequest(
        clientId,redirectUrl,issuer:issuer,
        scopes:['openid','profile','deal:write','order:read'],
      ),
    );
    final accessToken=tokens.accessToken;
    if (accessToken == null || accessToken.isEmpty) {
      throw StateError('No access token returned');
    }
    return accessToken;
  }
}
