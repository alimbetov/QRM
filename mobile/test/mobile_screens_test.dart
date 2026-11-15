import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:zakaz_mobile/buyer_auth.dart';
import 'package:zakaz_mobile/buyer_screen.dart';
import 'package:zakaz_mobile/catalog_screen.dart';
import 'package:zakaz_mobile/qr_screen.dart';
import 'package:zakaz_mobile/zakaz_api.dart';

const endpoint='https://staging.example.test';
final qrToken=List.filled(43,'B').join();

http.Response utf8Json(String body) => http.Response(body,200,
  headers:{'content-type':'application/json; charset=utf-8'});

String catalogFixture({int total=1})=>jsonEncode({
  'page':0,'size':12,'total':total,
  'items':[{
    'listingId':'listing','productId':'short','sellerId':'seller',
    'qrToken':qrToken,'title':'Тестовый товар','category':'Категория',
    'productType':'PHYSICAL',
    'variants':[
      {'id':'one','code':'SVC','unit':'SERVICE','priceKzt':null},
      {'id':'two','code':'BOX','unit':'PCS','priceKzt':'125000.50'},
      {'id':'three','code':'BULK','unit':'KG','priceKzt':'110000.00'}
    ]
  }]
});

String qrFixture()=>jsonEncode({
  'listingId':'listing','sellerId':'seller','title':'Тестовый товар',
  'category':'Категория','variants':[
    {'id':'sku','code':'BOX','unit':'PCS','priceKzt':'125000.50'}
  ]
});

String profileFixture()=>jsonEncode({
  'buyerId':'buyer-id','nickname':'Buyer123','maskedPhone':'••••4567',
  'phoneVerified':false,'email':'buyer@example.test','created':false
});

class FakeBuyerAuth implements BuyerAuthGateway {
  @override bool get isConfigured=>true;
  int signIns=0;
  int signOuts=0;
  bool failLogout=false;
  @override Future<BuyerSession> signIn() async {
    signIns++;
    return const BuyerSession(accessToken:'signed',idToken:'id-token');
  }
  @override Future<void> signOut(BuyerSession session) async {
    signOuts++;
    if(failLogout) throw StateError('Provider unavailable');
  }
}

void main() {
  testWidgets('catalog error offers retry and does not display fake items',(tester) async {
    int calls=0;
    final api=ZakazApi(endpoint,client:MockClient((_) async {
      calls++;
      if(calls==1)return http.Response('',503);
      return utf8Json(catalogFixture());
    }));
    await tester.pumpWidget(MaterialApp(home:CatalogScreen(api:api)));
    await tester.pumpAndSettle();
    expect(find.text('Повторить загрузку'),findsOneWidget);
    expect(find.text('Тестовый товар'),findsNothing);
    await tester.tap(find.text('Повторить загрузку'));
    await tester.pumpAndSettle();
    expect(find.text('Тестовый товар'),findsOneWidget);
    expect(calls,2);
  });

  testWidgets('multi-unit catalog does not force buyer into first service SKU',(tester) async {
    final api=ZakazApi(endpoint,client:MockClient((_) async =>
      utf8Json(catalogFixture())));
    await tester.pumpWidget(MaterialApp(home:CatalogScreen(api:api)));
    await tester.pumpAndSettle();
    expect(find.text('Запросить предложение · PCS'),findsOneWidget);
    expect(find.text('Запросить предложение · KG'),findsOneWidget);
    expect(find.text('Запросить предложение · SERVICE'),findsNothing);
    await tester.ensureVisible(find.text('Запросить предложение · PCS'));
    await tester.tap(find.text('Запросить предложение · PCS'));
    await tester.pumpAndSettle();
    expect(find.text('Товар short · PCS'),findsOneWidget);
  });

  testWidgets('QR load failure is recoverable and invalid contact gets feedback',(tester) async {
    int views=0;
    int contacts=0;
    final api=ZakazApi(endpoint,client:MockClient((req) async {
      if(req.method=='GET') {
        views++;
        return views==1?http.Response('',503):utf8Json(qrFixture());
      }
      contacts++;
      expect(req.url.path,'/api/v1/public/q/'+qrToken+'/leads');
      expect(jsonDecode(req.body)['phone'],'+77001234567');
      return http.Response('',202);
    }));
    await tester.pumpWidget(MaterialApp(home:QrScreen(api:api,token:qrToken)));
    await tester.pumpAndSettle();
    expect(find.text('Повторить загрузку'),findsOneWidget);
    await tester.tap(find.text('Повторить загрузку'));
    await tester.pumpAndSettle();
    expect(find.text('Тестовый товар'),findsOneWidget);
    await tester.ensureVisible(find.text('Отправить обращение'));
    await tester.tap(find.text('Отправить обращение'));
    await tester.pumpAndSettle();
    expect(find.text('Подтвердите согласие на обработку номера.'),findsOneWidget);
    await tester.tap(find.byType(CheckboxListTile));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Отправить обращение'));
    await tester.pumpAndSettle();
    expect(find.text('Укажите номер в формате +77001234567.'),findsOneWidget);
    await tester.enterText(find.byType(TextField),'+77001234567');
    await tester.tap(find.text('Отправить обращение'));
    await tester.pumpAndSettle();
    expect(find.text('Обращение принято, заказ не создан.'),findsOneWidget);
    expect(contacts,1);
  });

  testWidgets('failed buyer profile lookup is recoverable without another OIDC login',(tester) async {
    int lookups=0;
    final fake=FakeBuyerAuth();
    final api=ZakazApi(endpoint,client:MockClient((req) async {
      if(req.url.path=='/api/v1/buyers/me') {
        lookups++;
        return lookups==1?http.Response('',503):utf8Json(profileFixture());
      }
      return http.Response('',500);
    }));
    await tester.pumpWidget(MaterialApp(home:BuyerScreen(
      api:api,productId:'short',unit:'PCS',authGateway:fake)));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Войти через OIDC / PKCE'));
    await tester.pumpAndSettle();
    expect(find.text('Повторить проверку профиля'),findsOneWidget);
    expect(find.text('Создать BuyerDemand'),findsNothing);
    await tester.tap(find.text('Повторить проверку профиля'));
    await tester.pumpAndSettle();
    expect(fake.signIns,1);
    expect(lookups,2);
    expect(find.text('Создать BuyerDemand'),findsOneWidget);
  });

  testWidgets('failed provider logout retains the local session for a retry',(tester) async {
    final fake=FakeBuyerAuth()..failLogout=true;
    final api=ZakazApi(endpoint,client:MockClient((_) async =>
      utf8Json(profileFixture())));
    await tester.pumpWidget(MaterialApp(home:BuyerScreen(
      api:api,productId:'short',unit:'PCS',authGateway:fake)));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Войти через OIDC / PKCE'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Выйти через Keycloak'));
    await tester.pumpAndSettle();
    expect(find.text('Выйти через Keycloak'),findsOneWidget);
    expect(find.text('Войти через OIDC / PKCE'),findsNothing);
    fake.failLogout=false;
    await tester.tap(find.text('Выйти через Keycloak'));
    await tester.pumpAndSettle();
    expect(find.text('Войти через OIDC / PKCE'),findsOneWidget);
  });
  testWidgets('QR retries reuse one idempotency key after transient failure',(tester) async {
    int attempts=0;
    final keys=<String>[];
    final api=ZakazApi(endpoint,client:MockClient((req) async {
      if(req.method=='GET')return utf8Json(qrFixture());
      attempts++;
      keys.add(req.headers['idempotency-key']!);
      return http.Response('',attempts==1?503:202);
    }));
    await tester.pumpWidget(MaterialApp(home:QrScreen(api:api,token:qrToken)));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField),'+77001234567');
    await tester.tap(find.byType(CheckboxListTile));
    await tester.ensureVisible(find.text('Отправить обращение'));
    await tester.tap(find.text('Отправить обращение'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Обращение не принято'),findsOneWidget);
    await tester.tap(find.text('Отправить обращение'));
    await tester.pumpAndSettle();
    expect(find.text('Обращение принято, заказ не создан.'),findsOneWidget);
    expect(attempts,2);
    expect(keys[0],keys[1]);
  });

  testWidgets('old QR callback cannot mark a newly displayed listing accepted',(tester) async {
    final otherToken=List.filled(43,'C').join();
    final pending=Completer<http.Response>();
    final api=ZakazApi(endpoint,client:MockClient((req) async {
      if(req.method=='GET')return utf8Json(qrFixture());
      if(req.url.path.contains('/'+qrToken+'/leads'))return pending.future;
      return http.Response('',202);
    }));
    await tester.pumpWidget(MaterialApp(home:QrScreen(api:api,token:qrToken)));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField),'+77001234567');
    await tester.tap(find.byType(CheckboxListTile));
    await tester.ensureVisible(find.text('Отправить обращение'));
    await tester.tap(find.text('Отправить обращение'));
    await tester.pump();
    await tester.pumpWidget(MaterialApp(home:QrScreen(api:api,token:otherToken)));
    await tester.pumpAndSettle();
    pending.complete(http.Response('',202));
    await tester.pumpAndSettle();
    expect(find.text('Обращение принято, заказ не создан.'),findsNothing);
    expect(find.text('Отправить обращение'),findsOneWidget);
  });


  testWidgets('buyer confirms PLACED cancellation and sees server status',(tester) async {
    final fake=FakeBuyerAuth();
    var cancelled=false;
    var cancellations=0;
    final api=ZakazApi(endpoint,client:MockClient((req) async {
      final path=req.url.path;
      if(path=='/api/v1/buyers/me')return utf8Json(profileFixture());
      if(path=='/api/v1/buyers/demands' && req.method=='POST') {
        return http.Response('{"demandId":"demand-1"}',201);
      }
      if(path=='/api/v1/buyers/demands/demand-1/bids') {
        return utf8Json(jsonEncode({
          'demandId':'demand-1','acceptedOrderId':null,
          'bids':[{
            'bidId':'bid-1','sellerId':'seller-1',
            'totalKzt':'100.00','method':'PICKUP','deliveryFeeKzt':'0.00',
            'lines':[]
          }]
        }));
      }
      if(path.endsWith('/bids/bid-1/accept')) {
        return http.Response('{"orderId":"order-1"}',201);
      }
      if(path=='/api/v1/buyers/orders/order-1' && req.method=='GET') {
        return utf8Json(jsonEncode({
          'orderId':'order-1','status':cancelled?'CANCELLED_BY_BUYER':'PLACED',
          'publicNumber':'ZK-0000-0001','currency':'KZT',
          'totalAmount':'100.00','deliveryFee':'0.00','lines':[]
        }));
      }
      if(path=='/api/v1/buyers/orders/order-1/cancel' && req.method=='POST') {
        cancellations++;
        cancelled=true;
        return http.Response('',204);
      }
      return http.Response('',500);
    }));
    await tester.pumpWidget(MaterialApp(home:BuyerScreen(
      api:api,productId:'product-1',unit:'PCS',authGateway:fake)));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Войти через OIDC / PKCE'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Создать BuyerDemand'));
    await tester.tap(find.text('Создать BuyerDemand'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Принять и создать заказ'));
    await tester.tap(find.text('Принять и создать заказ'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Отменить заказ'));
    await tester.tap(find.text('Отменить заказ'));
    await tester.pumpAndSettle();
    expect(find.text('Подтвердить отмену'),findsOneWidget);
    expect(cancellations,0);
    await tester.tap(find.text('Подтвердить отмену'));
    await tester.pumpAndSettle();
    expect(cancellations,1);
    expect(find.text('Статус: CANCELLED_BY_BUYER'),findsOneWidget);
    expect(find.text('Отменить заказ'),findsNothing);
  });
}
