import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'package:flutter/material.dart';
import 'buyer_auth.dart';
import 'zakaz_api.dart';
import 'mobile_validation.dart';

class BuyerScreen extends StatefulWidget {
  final ZakazApi api;
  final String productId;
  final String unit;
  final BuyerAuthGateway? authGateway;
  const BuyerScreen({required this.api,required this.productId,
                     required this.unit,this.authGateway,super.key});
  @override State<BuyerScreen> createState()=>_BuyerScreenState();
}

class _BuyerScreenState extends State<BuyerScreen> {
  final nickname=TextEditingController();
  final phone=TextEditingController();
  final city=TextEditingController(text:'Алматы');
  final quantity=TextEditingController(text:'1');
  late final BuyerAuthGateway auth;
  @override void initState(){
    super.initState();
    auth=widget.authGateway ?? BuyerAuth();
  }
  final keys=<String,String>{};
  BuyerSession? session;
  String? token,demandId,orderId,error;
  Map<String,dynamic>? quotes,order,profile;
  bool busy=false,enrollmentChecked=false,consent=false;
  @override void dispose(){
    session=null;token=null;nickname.dispose();phone.dispose();city.dispose();quantity.dispose();super.dispose();
  }

  String nonce(){
    final random=Random.secure();
    return base64UrlEncode(List<int>.generate(18,(_)=>random.nextInt(256))).replaceAll('=','');
  }

  Future<void> action(Future<void> Function() work) async {
    if(busy)return;
    setState((){busy=true;error=null;});
    try {await work();}
    catch (e) {
      if(!mounted)return;
      setState(() {
        if(e is TimeoutException) {
          error='Сервер не ответил. Проверьте сеть и повторите действие.';
        } else if(e is ZakazApiError && e.statusCode==401) {
          error='Срок действия авторизации истёк. Выйдите и войдите снова.';
        } else if(e is ZakazApiError && e.statusCode==409) {
          error='Статус заказа изменился. Обновите заказ и повторите действие.';
        } else if(e is ZakazApiError && e.statusCode==403) {
          error='Нет доступа к этой операции. Проверьте подтверждение email и права.';
        } else if(e is FormatException || e is ArgumentError) {
          error='Проверьте введённые данные или корректность ответа сервера.';
        } else {
          error='Операция не выполнена. Проверьте соединение и повторите.';
        }
      });
    }
    finally {if(mounted)setState(()=>busy=false);}
  }

  Future<void> login()=>action(()async {
    final result=await auth.signIn();
    if(!mounted)return;
    // Keep the signed-in session if a subsequent profile GET fails transiently.
    // The user can retry the GET without another browser authentication.
    setState(() {
      session=result;
      token=result.accessToken;
      profile=null;
      enrollmentChecked=false;
      demandId=null;
      orderId=null;
      quotes=null;
      order=null;
      keys.clear();
    });
    await loadProfileDirect();
  });

  Future<void> loadProfileDirect() async {
    final current=token;
    if(current==null)throw StateError('Not signed in');
    final found=await widget.api.buyerProfile(current);
    if(mounted)setState((){profile=found;enrollmentChecked=true;});
  }
  Future<void> retryProfile()=>action(loadProfileDirect);

  Future<void> logout()=>action(()async {
    final current=session;
    if(current==null)throw StateError('No active OIDC session');
    await auth.signOut(current);
    if(mounted)setState((){
      session=null;token=null;demandId=null;orderId=null;
      quotes=null;order=null;profile=null;enrollmentChecked=false;
      consent=false;keys.clear();
    });
  });

  Future<void> enroll()=>action(()async {
    if(token==null || !validBuyerNickname(nickname.text) ||
       !validContactPhone(phone.text) || !consent) {
      throw const FormatException('Invalid buyer enrollment');
    }
    final found=await widget.api.enrollBuyer(
      token!,nickname.text,phone.text.trim(),consent);
    if(mounted)setState(()=>profile=found);
  });

  Future<void> create()=>action(()async {
    if(token==null || city.text.trim().isEmpty ||
       !validDemandQuantity(quantity.text,widget.unit)){
      throw const FormatException('Invalid buyer demand');
    }
    final id=await widget.api.createDemand(token!,widget.productId,widget.unit,
                                          city.text.trim(),quantity.text);
    if(mounted)setState(()=>demandId=id);
    await refreshBidsDirect();
  });

  Future<void> refreshBidsDirect() async {
    final data=await widget.api.bids(token!,demandId!);
    if(mounted)setState((){
      quotes=data;
      orderId=data['acceptedOrderId'] as String? ?? orderId;
    });
    if(orderId!=null && mounted)await refreshOrderDirect();
  }
  Future<void> refreshBids()=>action(refreshBidsDirect);

  Future<void> accept(String bidId)=>action(()async {
    final key=keys.putIfAbsent(bidId,nonce);
    final id=await widget.api.accept(token!,demandId!,bidId,key);
    if(mounted)setState(()=>orderId=id);
    await refreshOrderDirect();
  });

  Future<void> refreshOrderDirect() async {
    final data=await widget.api.order(token!,orderId!);
    if(mounted)setState(()=>order=data);
  }
  Future<void> refreshOrder()=>action(refreshOrderDirect);

  Future<void> cancelPlacedOrder() async {
    if(busy || token==null || orderId==null || order?['status']!='PLACED')return;
    final approved=await showDialog<bool>(
      context:context,
      builder:(dialogContext)=>AlertDialog(
        title:const Text('Отменить заказ?'),
        content:const Text(
          'Отмена доступна только до подтверждения продавцом. '
          'После подтверждения потребуется отдельное согласование.'),
        actions:[
          TextButton(onPressed:()=>Navigator.of(dialogContext).pop(false),
            child:const Text('Назад')),
          FilledButton(onPressed:()=>Navigator.of(dialogContext).pop(true),
            child:const Text('Подтвердить отмену')),
        ],
      ),
    );
    if(approved!=true || !mounted)return;
    await action(()async {
      await widget.api.cancelOrder(token!,orderId!);
      await refreshOrderDirect();
    });
  }


  @override Widget build(BuildContext context) {
    final bids=(quotes?['bids'] as List<dynamic>? ?? []);
    return Scaffold(appBar:AppBar(title:const Text('Заказ · ZAKAZ')),
      body:ListView(padding:const EdgeInsets.all(16),children:[
        Text('Запросить предложение',style:Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height:8),
        Text('Товар '+shortPublicId(widget.productId)+' · '+widget.unit),
        const SizedBox(height:8),
        const Text('Сначала продавец делает предложение, затем покупатель явно принимает его. Статус PLACED не означает резерв или оплату.'),
        const Divider(height:32),
        if(!auth.isConfigured)const Text('OIDC не настроен. Авторизованный заказ недоступен.'),
        if(auth.isConfigured && token==null)FilledButton(
          onPressed:busy?null:login,child:const Text('Войти через OIDC / PKCE')),
        if(token!=null)OutlinedButton(
          onPressed:busy?null:logout,child:const Text('Выйти через Keycloak')),
        if(token!=null && !enrollmentChecked) ...[
          const Text('Профиль покупателя не загружен. Повторите проверку, если произошла ошибка сети.'),
          OutlinedButton(onPressed:busy?null:retryProfile,
            child:const Text('Повторить проверку профиля')),
        ],
        if(token!=null && enrollmentChecked && profile==null) ...[
          const Text('Завершите регистрацию через проверенный email. Телефон хранится зашифрованным, но не подтверждается SMS и не используется для входа.'),
          TextField(controller:nickname,decoration:const InputDecoration(labelText:'Никнейм')),
          TextField(controller:phone,keyboardType:TextInputType.phone,
            decoration:const InputDecoration(labelText:'Телефон (+77001234567)')),
          CheckboxListTile(
            value:consent,title:const Text('Согласен на хранение номера для обработки заказа'),
            onChanged:busy?null:(v)=>setState(()=>consent=v??false),
          ),
          FilledButton(onPressed:busy?null:enroll,
            child:const Text('Создать профиль покупателя')),
        ],
        if(token!=null && profile!=null && demandId==null) ...[
          TextField(controller:city,decoration:const InputDecoration(labelText:'Город')),
          TextField(controller:quantity,decoration:const InputDecoration(labelText:'Количество'),
                    keyboardType:const TextInputType.numberWithOptions(decimal:true)),
          const SizedBox(height:12),
          FilledButton(onPressed:busy?null:create,child:const Text('Создать BuyerDemand')),
        ],
        if(demandId!=null && orderId==null) ...[
          Text('Заявка: $demandId'),
          OutlinedButton(onPressed:busy?null:refreshBids,child:const Text('Обновить предложения')),
          if(bids.isEmpty)const Text('Пока нет предложений продавцов. Обновите список позже.'),
          ...bids.map((data){
            final bid=data as Map<String,dynamic>;
            final id=bid['bidId'] as String;
            return Card(child:Padding(padding:const EdgeInsets.all(12),child:Column(
              crossAxisAlignment:CrossAxisAlignment.start,children:[
                Text('Продавец '+shortPublicId(bid['sellerId'] as String?)),
                Text('Итого: ${bid['totalKzt']} ₸'),
                Text('Способ: ${bid['method']}; доставка: ${bid['deliveryFeeKzt']} ₸'),
                FilledButton(onPressed:busy?null:()=>accept(id),
                  child:const Text('Принять и создать заказ')),
              ])));
          }),
        ],
        if(orderId!=null) ...[
          Text('Заказ: $orderId'),
          OutlinedButton(onPressed:busy?null:refreshOrder,child:const Text('Обновить статус')),
          if(order!=null) ...[
            Text('Номер: ${order!['publicNumber']}'),
            Text('Статус: ${order!['status']}'),
            if(order!['status']=='PLACED')
              OutlinedButton(
                onPressed:busy?null:cancelPlacedOrder,
                child:const Text('Отменить заказ')),
            if(order!['status']=='CANCELLED_BY_BUYER')
              const Text('Заказ отменён до резервирования товара.'),
            Text('Сумма: ${order!['totalAmount']} ${order!['currency']}'),
          ],
        ],
        if(busy)const Center(child:CircularProgressIndicator()),
        if(error!=null)Text(error!,style:TextStyle(color:Theme.of(context).colorScheme.error)),
      ]),
    );
  }
}
