import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:zakaz_mobile/zakaz_api.dart';

void main() {
  test('public catalog uses real GET with bounded page and no fake fallback', () async {
    final client=MockClient((request) async {
      expect(request.method,'GET');
      expect(request.url.path,'/api/v1/public/catalog');
      expect(request.url.queryParameters['page'],'0');
      expect(request.headers.containsKey('authorization'),isFalse);
      return http.Response(jsonEncode({'page':0,'size':12,'total':0,'items':[]}),200,
        headers:{'content-type':'application/json'});
    });
    final api=ZakazApi('http://localhost:8080',client:client);
    expect((await api.catalog())['items'],isEmpty);
    api.dispose();
  });

  test('accepted seller bid sends existing idempotency key with verified bearer', () async {
    final client=MockClient((request) async {
      expect(request.method,'POST');
      expect(request.url.path,'/api/v1/buyers/demands/demand/bids/bid/accept');
      expect(request.headers['authorization'],'Bearer signed-jwt');
      expect(request.headers['idempotency-key'],'stable-key');
      return http.Response('{"orderId":"order"}',201,
        headers:{'content-type':'application/json'});
    });
    final api=ZakazApi('https://staging.example.test',client:client);
    expect(await api.accept('signed-jwt','demand','bid','stable-key'),'order');
    api.dispose();
  });

  test('no fake order on API failure', () async {
    final client=MockClient((request) async =>http.Response('',403));
    final api=ZakazApi('https://staging.example.test',client:client);
    expect(()=>api.order('buyer-jwt','foreign'),throwsA(isA<StateError>()));
    api.dispose();
  });

  test('insecure external API address is prohibited', () {
    expect(()=>ZakazApi('http://example.com'),throwsArgumentError);
  });
}
