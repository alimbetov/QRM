/// Small, deterministic navigation contract for public React pages in Flutter.
/// No JWT, cookie or JavaScript bridge is used for authenticated checkout.
enum HybridRouteType { publicPage, nativeBuyer, blocked }

class HybridRoute {
  const HybridRoute(this.type, {this.productId, this.unit});
  final HybridRouteType type;
  final String? productId;
  final String? unit;
}

class HybridRoutePolicy {
  HybridRoutePolicy(String webBaseUrl) : root = _validateRoot(Uri.parse(webBaseUrl));

  final Uri root;

  static final RegExp _uuid = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  );
  static final RegExp _qr = RegExp(r'^[A-Za-z0-9_-]{43}$');
  static const Set<String> _units = {
    'PCS', 'KG', 'G', 'L', 'ML', 'M', 'M2', 'M3', 'HOUR',
  };
  static const Set<String> _catalogParams = {
    'q', 'categoryCode', 'productType', 'page',
  };

  static Uri _validateRoot(Uri uri) {
    final localHost = {
      'localhost', '127.0.0.1', '10.0.2.2',
    }.contains(uri.host);
    if (uri.host.isEmpty ||
        !(uri.scheme == 'https' || (uri.scheme == 'http' && localHost)) ||
        uri.userInfo.isNotEmpty ||
        (uri.path.isNotEmpty && uri.path != '/') ||
        uri.hasQuery || uri.hasFragment) {
      throw ArgumentError('A trusted HTTPS React origin is required');
    }
    return uri.resolve('/');
  }

  bool _sameOrigin(Uri uri) =>
      uri.scheme == root.scheme &&
      uri.host == root.host &&
      uri.port == root.port &&
      uri.userInfo.isEmpty;

  HybridRoute classify(String url) {
    final uri = Uri.tryParse(url);
    if (uri == null || !_sameOrigin(uri) || uri.hasFragment) {
      return const HybridRoute(HybridRouteType.blocked);
    }

    if (uri.path == '/buy') {
      final params = uri.queryParametersAll;
      if (params.length != 2 ||
          params['productId']?.length != 1 ||
          params['unit']?.length != 1) {
        return const HybridRoute(HybridRouteType.blocked);
      }
      final productId = params['productId']!.single;
      final unit = params['unit']!.single;
      if (!_uuid.hasMatch(productId) || !_units.contains(unit)) {
        return const HybridRoute(HybridRouteType.blocked);
      }
      return HybridRoute(HybridRouteType.nativeBuyer,
          productId: productId, unit: unit);
    }

    if (uri.path == '/') {
      final params = uri.queryParametersAll;
      if (params.keys.any((key) => !_catalogParams.contains(key)) ||
          params.values.any((values) => values.length != 1)) {
        return const HybridRoute(HybridRouteType.blocked);
      }
      return const HybridRoute(HybridRouteType.publicPage);
    }

    if (uri.pathSegments.length == 2 &&
        uri.pathSegments.first == 'q' &&
        _qr.hasMatch(uri.pathSegments.last) &&
        !uri.hasQuery) {
      return const HybridRoute(HybridRouteType.publicPage);
    }

    // In particular: /auth/*, /logout, /seller/* and unknown links must
    // not be opened inside the embedded user agent.
    return const HybridRoute(HybridRouteType.blocked);
  }
}
