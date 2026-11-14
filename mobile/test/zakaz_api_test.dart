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

  test('enrollment has no client-controlled buyer identity or phone verified flag', () async {
    final client=MockClient((request) async {
      expect(request.url.path,'/api/v1/buyers/enroll');
      expect(request.method,'POST');
      expect(request.headers['authorization'],'Bearer signed-buyer-token');
      final payload=jsonDecode(request.body) as Map<String,dynamic>;
      expect(payload.keys,containsAll(['nickname','phone','consentToStorePhone']));
      expect(payload.containsKey('buyerId'),isFalse);
      expect(payload.containsKey('email_verified'),isFalse);
      expect(payload['consentToStorePhone'],isTrue);
      return http.Response(jsonEncode({
        'buyerId':'server-id','nickname':'TestBuyer','phoneVerified':false,
        'maskedPhone':'••••0000','email':'buyer@verified.test','created':true
      }),201,headers:{'content-type':'application/json; charset=utf-8'});
    });
    final api=ZakazApi('https://staging.example.test',client:client);
    final registered=await api.enrollBuyer('signed-buyer-token','TestBuyer','+77000000000',true);
    expect(registered['phoneVerified'],false);
    api.dispose();
  });

  test('profile 404 means not enrolled while access denied stays an error', () async {
    int calls=0;
    final client=MockClient((request) async {
      expect(request.url.path,'/api/v1/buyers/me');
      expect(request.headers['authorization'],'Bearer signed-buyer-token');
      calls++;
      return http.Response('',calls==1?404:403);
    });
    final api=ZakazApi('https://staging.example.test',client:client);
    expect(await api.buyerProfile('signed-buyer-token'),isNull);
    await expectLater(api.buyerProfile('signed-buyer-token'),
      throwsA(isA<ZakazApiError>().having((e)=>e.statusCode,'statusCode',403)));
    api.dispose();
  });

  test('insecure external API address is prohibited', () {
    expect(()=>ZakazApi('http://example.com'),throwsArgumentError);
  });

  test('buyer cancellation uses bearer POST and no fake response', () async {
    var calls=0;
    final api=ZakazApi('https://staging.example.test',client:MockClient((req) async {
      calls++;
      expect(req.method,'POST');
      expect(req.url.path,'/api/v1/buyers/orders/order-id/cancel');
      expect(req.headers['authorization'],'Bearer buyer-jwt');
      expect(req.body,isEmpty);
      return http.Response('',204);
    }));
    await api.cancelOrder('buyer-jwt','order-id');
    expect(calls,1);
    api.dispose();
  });
  test('buyer cancellation returns 409 business conflict on confirmed order', () async {
    final api=ZakazApi('https://staging.example.test',client:MockClient((_) async =>
      http.Response('{"code":"BUSINESS_CONFLICT"}',409)));
    await expectLater(api.cancelOrder('buyer-jwt','already-confirmed'),
      throwsA(isA<ZakazApiError>().having((e)=>e.statusCode,'status',409)));
    api.dispose();
  });
}
