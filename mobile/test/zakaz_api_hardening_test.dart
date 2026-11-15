import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:zakaz_mobile/zakaz_api.dart';

const origin='https://staging.example.test';
final token=List.filled(43,'A').join();

void main() {
  test('stalled HTTP requests time out instead of permanently blocking UI',() async {
    final pending=Completer<http.Response>();
    final api=ZakazApi(origin,requestTimeout:const Duration(milliseconds:30),
      client:MockClient((_)=>pending.future));
    await expectLater(api.catalog(),throwsA(isA<TimeoutException>()));
    pending.complete(http.Response('{"page":0,"size":12,"total":0,"items":[]}',200));
    api.dispose();
  });

  test('API base accepts only root HTTPS without credentials or fragments',() {
    for(final invalid in [
      'http://example.com','https://user:password@example.com',
      'https://example.com/private','https://example.com/?token=private',
      'https://example.com/#redirect','https://',
    ]) {
      expect(()=>ZakazApi(invalid),throwsA(isA<Object>()),reason:invalid);
    }
    final valid=ZakazApi('https://example.com/');
    expect(valid.url('/api/v1/public/catalog').origin,'https://example.com');
    expect(()=>valid.url('//evil.example/steal'),throwsArgumentError);
    valid.dispose();
  });

  test('malformed catalog is not presented as empty listings',() async {
    final api=ZakazApi(origin,client:MockClient((_) async =>
      http.Response('{"page":0,"size":12,"total":0}',200)));
    await expectLater(api.catalog(),throwsA(isA<FormatException>()));
    api.dispose();
  });

  test('malformed SKU and forged QR tokens cannot open a buyer route',() async {
    var calls=0;
    final api=ZakazApi(origin,client:MockClient((_) async {
      calls++;
      return http.Response(jsonEncode({
        'page':0,'size':12,'total':1,
        'items':[{
          'listingId':'listing','productId':'product','qrToken':token,
          'title':'Product','productType':'PHYSICAL',
          'variants':[{'id':'sku','code':'SKU','unit':'PCS','priceKzt':12.50}]
        }]
      }),200);
    }));
    await expectLater(api.catalog(),throwsA(isA<FormatException>()));
    await expectLater(api.qr('../admin'),throwsArgumentError);
    expect(calls,1);
    api.dispose();
  });

  test('accepted order must contain a server-generated order ID',() async {
    final api=ZakazApi(origin,client:MockClient((_) async =>http.Response('{}',201)));
    await expectLater(api.accept('signed','demand','bid','stable'),
      throwsA(isA<FormatException>()));
    api.dispose();
  });

  test('missing bids array is a contract error, not no seller responses',() async {
    final api=ZakazApi(origin,client:MockClient((_) async =>
      http.Response('{"demandId":"d","acceptedOrderId":null}',200)));
    await expectLater(api.bids('signed','d'),throwsA(isA<FormatException>()));
    api.dispose();
  });

  test('buyer calls never send an empty bearer or malformed idempotency key',() async {
    var calls=0;
    final api=ZakazApi(origin,client:MockClient((_) async {
      calls++;
      return http.Response('{}',200);
    }));
    await expectLater(api.buyerProfile(''),throwsArgumentError);
    await expectLater(api.accept('signed','d','b','with spaces'),throwsArgumentError);
    await expectLater(api.lead(token,'bad-phone','stable'),throwsArgumentError);
    expect(calls,0);
    api.dispose();
  });

  test('valid QR detail has explicit variants and safe price strings',() async {
    final api=ZakazApi(origin,client:MockClient((req) async {
      expect(req.url.path,'/api/v1/public/q/'+token);
      return http.Response(jsonEncode({
        'listingId':'listing','sellerId':'seller','title':'Phone',
        'category':'Phones',
        'variants':[{'id':'sku','code':'1','unit':'PCS','priceKzt':'100.00'}]
      }),200);
    }));
    expect((await api.qr(token))['title'],'Phone');
    api.dispose();
  });
  test('order amounts above JavaScript safe integer range remain exact strings',() async {
    final api=ZakazApi(origin,client:MockClient((_) async =>
      http.Response(jsonEncode({
        'orderId':'order-1','status':'PLACED','currency':'KZT',
        'totalAmount':'9007199254740993.01','deliveryFee':'0.00',
        'lines':[{'skuId':'sku','unit':'PCS','quantity':'1.000',
          'lineTotal':'9007199254740993.01'}]
      }),200)));
    final order=await api.order('signed','order-1');
    expect(order['totalAmount'],'9007199254740993.01');
    expect((order['lines'] as List).first['lineTotal'],'9007199254740993.01');
    api.dispose();
  });

  test('numeric order money is rejected to avoid a rounded checkout total',() async {
    final api=ZakazApi(origin,client:MockClient((_) async =>
      http.Response(jsonEncode({
        'orderId':'order-1','status':'PLACED','currency':'KZT',
        'totalAmount':123.45,'deliveryFee':0,
        'lines':[{'skuId':'sku','quantity':'1.000','lineTotal':'123.45'}]
      }),200)));
    await expectLater(api.order('signed','order-1'),throwsA(isA<FormatException>()));
    api.dispose();
  });

}
