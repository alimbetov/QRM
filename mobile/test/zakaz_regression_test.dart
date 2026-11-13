import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:zakaz_mobile/zakaz_api.dart';

void main() {
  test('conflicting acceptance preserves idempotency header and fails closed', () async {
    final client=MockClient((req) async {
      expect(req.method,'POST');
      expect(req.headers['idempotency-key'],'accept-key');
      expect(req.headers['authorization'],'Bearer buyer-jwt');
      return http.Response('{"error":"conflict"}',409);
    });
    final api=ZakazApi('https://staging.example.test',client:client);
    await expectLater(api.accept('buyer-jwt','d','b','accept-key'),
      throwsA(isA<ZakazApiError>().having((e)=>e.statusCode,'status',409)));
    api.dispose();
  });

  test('buyer demand has server-owned identity and preserves requested quantity', () async {
    final client=MockClient((req) async {
      expect(req.url.path,'/api/v1/buyers/demands');
      final json=jsonDecode(req.body) as Map<String,dynamic>;
      expect(json.containsKey('buyerId'),isFalse);
      expect(json['city'],'Алматы');
      final lines=json['lines'] as List<dynamic>;
      expect(lines.single,{'productId':'canonical','unit':'PCS','quantity':'2'});
      expect(DateTime.parse(json['expiresAt'] as String).isAfter(DateTime.now().toUtc()),isTrue);
      return http.Response('{"demandId":"server-demand"}',201,
        headers:{'content-type':'application/json'});
    });
    final api=ZakazApi('https://staging.example.test',client:client);
    expect(await api.createDemand('buyer-jwt','canonical','PCS','Алматы','2'),'server-demand');
    api.dispose();
  });

  test('foreign order and revoked token never return a fabricated response', () async {
    final client=MockClient((req) async {
      expect(req.headers['authorization'],'Bearer denied-token');
      return http.Response('{"message":"denied"}',403);
    });
    final api=ZakazApi('https://staging.example.test',client:client);
    await expectLater(api.order('denied-token','foreign'),
      throwsA(isA<ZakazApiError>().having((e)=>e.statusCode,'status',403)));
    await expectLater(api.buyerProfile('denied-token'),
      throwsA(isA<ZakazApiError>().having((e)=>e.statusCode,'status',403)));
    api.dispose();
  });

  test('lead submission is explicitly consented and idempotent', () async {
    final token=List.filled(43,'B').join();
    final client=MockClient((req) async {
      expect(req.method,'POST');
      expect(req.url.path,'/api/v1/public/q/' + token + '/leads');
      expect(req.headers['idempotency-key'],'lead-unique-key');
      expect(req.headers.containsKey('authorization'),isFalse);
      expect(jsonDecode(req.body),{'phone':'+77001234567','consent':true});
      return http.Response('',204);
    });
    final api=ZakazApi('https://staging.example.test',client:client);
    await api.lead(token,'+77001234567','lead-unique-key');
    api.dispose();
  });

  test('malformed API payload is an error, not a fake empty catalog', () async {
    final client=MockClient((req) async =>http.Response('[1,2,3]',200,
      headers:{'content-type':'application/json'}));
    final api=ZakazApi('https://staging.example.test',client:client);
    await expectLater(api.catalog(),throwsA(isA<FormatException>()));
    api.dispose();
  });
}
