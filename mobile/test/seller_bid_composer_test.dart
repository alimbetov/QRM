import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:zakaz_mobile/seller_bid_composer.dart';
import 'package:zakaz_mobile/zakaz_api.dart';

const seller='11111111-1111-4111-8111-111111111111';
const invite='22222222-2222-4222-8222-222222222222';
const demand='33333333-3333-4333-8333-333333333333';
const product='44444444-4444-4444-8444-444444444444';
const sku='55555555-5555-4555-8555-555555555555';
const origin='66666666-6666-4666-8666-666666666666';
const bid='77777777-7777-4777-8777-777777777777';

Map<String,dynamic> fixture()=>{
  'invitationId':invite,'demandId':demand,'destinationCity':'Almaty',
  'expiresAt':DateTime.now().toUtc().add(const Duration(hours:1)).toIso8601String(),
  'nextRevision':1,'lines':[{
    'productId':product,'productName':'Лампа','hccCode':'001',
    'unit':'PCS','quantity':'1.000','maxUnitPrice':'150.00',
    'skus':[{'skuId':sku,'code':'LAMP-1','priceKzt':'120.00',
      'availableByOrigin':{origin:'5.000'}}]
  }],
  'origins':[{'originLocationId':origin,'city':'Almaty','regionCode':'KZ-ALA',
    'address':'Test 1','type':'SHOP','pickup':true,'delivery':true}]
};

void main(){
  testWidgets('native seller bid uses real context and confirms exact KZT before POST',
      (tester) async {
    var bidCalls=0;
    final client=MockClient((request) async {
      expect(request.headers['authorization'],'Bearer seller-token');
      expect(request.url.path,'/api/v1/sellers/'+seller+'/invitations/'+invite+
        (request.method=='GET'?'/quote-context':'/bid'));
      if(request.method=='GET')return http.Response(jsonEncode(fixture()),200);
      bidCalls++;
      final payload=jsonDecode(request.body) as Map<String,dynamic>;
      expect(request.method,'POST');
      expect(payload['revision'],1);
      expect(payload['method'],'PICKUP');
      expect(payload['originLocationId'],origin);
      expect(payload['deliveryFee'],'0.00');
      expect((payload['lines'] as List).first['skuId'],sku);
      expect((payload['lines'] as List).first['unitPrice'],'120.00');
      return http.Response(jsonEncode({'bidId':bid}),201);
    });
    final api=ZakazApi('https://stage.example.test',client:client);
    await tester.pumpWidget(MaterialApp(home:SellerBidComposer(
      api:api,token:'seller-token',sellerId:seller,invitationId:invite)));
    await tester.pumpAndSettle();
    expect(find.text('Итого с доставкой: 120.00 KZT'),findsOneWidget);
    expect(bidCalls,0);
    await tester.ensureVisible(find.text('Отправить SellerBid'));
    await tester.tap(find.text('Отправить SellerBid'));
    await tester.pumpAndSettle();
    expect(bidCalls,0); // explicit confirmation required
    await tester.tap(find.text('Отправить').last);
    await tester.pumpAndSettle();
    expect(bidCalls,1);
    expect(find.text('Предложение отправлено. Покупатель может его принять.'),
      findsOneWidget);
    expect(find.text('Отправить SellerBid'),findsOneWidget);
    api.dispose();
  });

  testWidgets('409 does not display success and requires reloading', (tester) async {
    final client=MockClient((request) async {
      if(request.method=='GET')return http.Response(jsonEncode(fixture()),200);
      return http.Response('conflict',409);
    });
    await tester.pumpWidget(MaterialApp(home:SellerBidComposer(
      api:ZakazApi('https://stage.example.test',client:client),
      token:'seller-token',sellerId:seller,invitationId:invite)));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Отправить SellerBid'));
    await tester.tap(find.text('Отправить SellerBid'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Отправить').last);
    await tester.pumpAndSettle();
    expect(find.textContaining('HTTP 409'),findsOneWidget);
    expect(find.textContaining('Предложение отправлено'),findsNothing);
  });

  testWidgets('revoked invitation stays fail-closed without fabricated data',
      (tester) async {
    final client=MockClient((request) async =>http.Response('',404));
    await tester.pumpWidget(MaterialApp(home:SellerBidComposer(
      api:ZakazApi('https://stage.example.test',client:client),
      token:'seller-token',sellerId:seller,invitationId:invite)));
    await tester.pumpAndSettle();
    expect(find.textContaining('HTTP 404'),findsOneWidget);
    expect(find.text('Отправить SellerBid'),findsNothing);
  });
}
