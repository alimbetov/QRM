import 'package:flutter_test/flutter_test.dart';
import 'package:zakaz_mobile/hybrid_route_policy.dart';

const base = 'https://shop.zakaz.example';
const product = '1046ca0e-4004-4004-8004-a031ffede023';
final token = List.filled(43, 'A').join();

void main() {
  group('hybrid origin policy', () {
    test('accepts only HTTPS root or an explicit local emulator origin', () {
      expect(HybridRoutePolicy(base).root.toString(), '$base/');
      expect(
        HybridRoutePolicy('http://10.0.2.2:8080').root.host, '10.0.2.2',
      );
      for (final url in [
        'http://shop.zakaz.example',
        'https://buyer:password@shop.zakaz.example',
        'https://shop.zakaz.example/subdirectory',
        'https://shop.zakaz.example/?token=secret',
        'https://shop.zakaz.example/#secret',
        'javascript:alert(1)',
      ]) {
        expect(() => HybridRoutePolicy(url), throwsA(isA<ArgumentError>()),
            reason: url);
      }
    });
  });

  group('hybrid public navigation', () {
    final policy = HybridRoutePolicy(base);
    test('allows catalog search and valid QR pages', () {
      expect(policy.classify('$base/').type, HybridRouteType.publicPage);
      expect(
        policy.classify('$base/?q=drill&categoryCode=01020030000000000000')
            .type, HybridRouteType.publicPage,
      );
      expect(
        policy.classify('$base/q/$token').type,
        HybridRouteType.publicPage,
      );
    });

    test('routes a valid product request to native BuyerScreen', () {
      final route = policy.classify('$base/buy?productId=$product&unit=PCS');
      expect(route.type, HybridRouteType.nativeBuyer);
      expect(route.productId, product);
      expect(route.unit, 'PCS');
      expect(
        policy.classify('$base/buy?unit=KG&productId=$product').type,
        HybridRouteType.nativeBuyer,
      );
    });

    test('blocks external hosts, lookalikes and non-HTTPS navigation', () {
      for (final url in [
        'https://shop.zakaz.example.evil.test/',
        'https://evil.test/',
        'http://shop.zakaz.example/',
        'https://shop.zakaz.example:8443/',
        'javascript:alert(1)',
        'file:///etc/passwd',
        '$base/auth/callback',
        '$base/auth/signed-out',
        '$base/seller/orders',
        '$base/unknown',
      ]) {
        expect(policy.classify(url).type, HybridRouteType.blocked,
            reason: url);
      }
    });

    test('blocks forged purchase routes, duplicate params and fragments', () {
      for (final url in [
        '$base/buy',
        '$base/buy?productId=not-uuid&unit=PCS',
        '$base/buy?productId=$product&unit=SERVICE',
        '$base/buy?productId=$product&unit=LICENSE',
        '$base/buy?productId=$product&unit=PCS&unit=KG',
        '$base/buy?productId=$product&unit=PCS&access_token=secret',
        '$base/buy?orderId=$product',
        '$base/buy?productId=$product&unit=PCS#frag',
        '$base/?access_token=secret',
        '$base/?q=one&q=two',
        '$base/q/not-a-real-token',
      ]) {
        expect(policy.classify(url).type, HybridRouteType.blocked,
            reason: url);
      }
    });
  });
}
