import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:zakaz_mobile/buyer_auth.dart';
import 'package:zakaz_mobile/seller_inbox_screen.dart';
import 'package:zakaz_mobile/zakaz_api.dart';

const seller='11111111-1111-4111-8111-111111111111';
const order='22222222-2222-4222-8222-222222222222';
const lead='33333333-3333-4333-8333-333333333333';
const listing='44444444-4444-4444-8444-444444444444';
class FakeSellerAuth implements BuyerAuthGateway {
  @override bool get isConfigured=>true;
  int loginCount=0,logoutCount=0;
  @override Future<BuyerSession> signIn() async {
    loginCount++;
    return const BuyerSession(accessToken:'seller-jwt',idToken:'id-token');
  }
  @override Future<void> signOut(BuyerSession session) async {
    logoutCount++;
  }
}
http.Response success(Object data)=>http.Response(jsonEncode(data),200,
  headers:{'content-type':'application/json'});

void main(){
  testWidgets('seller flow: login, confirm, explicit callback reading and CONTACTED',(tester) async {
    final auth=FakeSellerAuth();
    var confirmed=false,contacted=false;
    var orderCalls=0,leadReads=0,confirmCalls=0,leadStatusCalls=0;
    final client=MockClient((req) async {
      expect(req.headers['authorization'],'Bearer seller-jwt');
      if(req.url.path=='/api/v1/sellers/me') {
        expect(req.method,'GET');
        return success([{'sellerId':seller,'role':'SELLER_ADMIN'}]);
      }
      if(req.url.path=='/api/v1/sellers/'+seller+'/orders' && req.method=='GET'){
        orderCalls++;
        return success({'page':0,'size':20,'total':1,'items':[{
          'orderId':order,'publicNumber':'ZK-0001-0001',
          'status':confirmed?'CONFIRMED':'PLACED',
          'totalAmount':'100.00','deliveryFee':'0.00','currency':'KZT',
          'createdAt':'2026-11-14T00:00:00Z','lines':[]
        }]});
      }
      if(req.url.path=='/api/v1/sellers/'+seller+'/orders/'+order+'/confirm'){
        expect(req.method,'POST');
        confirmCalls++;
        confirmed=true;
        return http.Response('',204);
      }
      if(req.url.path=='/api/v1/sellers/'+seller+'/leads'){
        expect(req.method,'GET');
        leadReads++;
        return success([{
          'leadId':lead,'listingId':listing,'phone':'+77011234567',
          'receivedAt':'2026-11-14T00:00:00Z',
          'status':contacted?'CONTACTED':'NEW'
        }]);
      }
      if(req.url.path=='/api/v1/sellers/'+seller+'/leads/'+lead+'/status'){
        expect(req.method,'PUT');
        expect(jsonDecode(req.body)['status'],'CONTACTED');
        leadStatusCalls++;
        contacted=true;
        return http.Response('',204);
      }
      return http.Response('',404);
    });
    final api=ZakazApi('https://staging.example.test',client:client);
    await tester.pumpWidget(MaterialApp(home:SellerInboxScreen(
      api:api,authGateway:auth)));
    await tester.pumpAndSettle();
    expect(leadReads,0);
    await tester.tap(find.text('Войти как продавец'));
    await tester.pumpAndSettle();
    expect(auth.loginCount,1);
    expect(orderCalls,1);
    expect(find.text('Заказ ZK-0001-0001 · PLACED'),findsOneWidget);
    await tester.ensureVisible(find.text('Подтвердить и зарезервировать'));
    await tester.tap(find.text('Подтвердить и зарезервировать'));
    await tester.pumpAndSettle();
    expect(confirmCalls,0);
    await tester.tap(find.text('Подтвердить').last);
    await tester.pumpAndSettle();
    expect(confirmCalls,1);
    expect(find.text('Заказ ZK-0001-0001 · CONFIRMED'),findsOneWidget);
    expect(find.text('Подтвердить и зарезервировать'),findsNothing);
    expect(leadReads,0);
    await tester.ensureVisible(find.text('Обратные звонки'));
    await tester.tap(find.text('Обратные звонки'));
    await tester.pumpAndSettle();
    expect(leadReads,1);
    expect(find.text('Телефон: +77011234567'),findsOneWidget);
    await tester.ensureVisible(find.text('Отметить «Связались»'));
    await tester.tap(find.text('Отметить «Связались»'));
    await tester.pumpAndSettle();
    expect(leadStatusCalls,0);
    await tester.tap(find.text('Продолжить'));
    await tester.pumpAndSettle();
    expect(leadStatusCalls,1);
    expect(find.text('Заявка на звонок · CONTACTED'),findsOneWidget);
    expect(find.text('Отметить «Связались»'),findsNothing);
    api.dispose();
  });

  test('seller HTTP rejects foreign membership and 409 never invents confirmation',() async {
    final api=ZakazApi('https://staging.example.test',
      client:MockClient((req) async {
        if(req.url.path=='/api/v1/sellers/me')
          return http.Response('Forbidden',403);
        return http.Response('{"code":"BUSINESS_CONFLICT"}',409);
      }));
    await expectLater(api.sellerAccounts('seller-jwt'),
      throwsA(isA<ZakazApiError>().having((e)=>e.statusCode,'status',403)));
    await expectLater(api.sellerOrderAction('seller-jwt',seller,order,'confirm'),
      throwsA(isA<ZakazApiError>().having((e)=>e.statusCode,'status',409)));
    api.dispose();
  });
}
