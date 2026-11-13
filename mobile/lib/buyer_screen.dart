import 'dart:convert';
import 'dart:math';
import 'package:flutter/material.dart';
import 'buyer_auth.dart';
import 'zakaz_api.dart';

class BuyerScreen extends StatefulWidget {
  final ZakazApi api;
  final String productId;
  final String unit;
  const BuyerScreen({required this.api,required this.productId,
                     required this.unit,super.key});
  @override State<BuyerScreen> createState()=>_BuyerScreenState();
}

class _BuyerScreenState extends State<BuyerScreen> {
  final city=TextEditingController(text:'Алматы');
  final quantity=TextEditingController(text:'1');
  final auth=BuyerAuth();
  final keys=<String,String>{};
  String? token,demandId,orderId,error;
  Map<String,dynamic>? quotes,order;
  bool busy=false;
  @override void dispose(){
    token=null;city.dispose();quantity.dispose();super.dispose();
  }

  String nonce(){
    final random=Random.secure();
    return base64UrlEncode(List<int>.generate(18,(_)=>random.nextInt(256))).replaceAll('=','');
  }

  Future<void> action(Future<void> Function() work) async {
    if(busy)return;
    setState((){busy=true;error=null;});
    try {await work();}
    catch (_) {if(mounted)setState(()=>error='Операция отклонена. Проверьте авторизацию, условия заказа и повторите.');}
    finally {if(mounted)setState(()=>busy=false);}
  }

  Future<void> login()=>action(()async {
    final result=await auth.signIn();
    if(mounted)setState(()=>token=result);
  });

  Future<void> create()=>action(()async {
    if(token==null || city.text.trim().isEmpty ||
       !RegExp(r'^\d{1,10}(\.\d{1,3})?$').hasMatch(quantity.text) ||
       double.tryParse(quantity.text)==null || double.parse(quantity.text)<=0){
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
    if(orderId!=null)await refreshOrderDirect();
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

  @override Widget build(BuildContext context) {
    final bids=(quotes?['bids'] as List<dynamic>? ?? []);
    return Scaffold(appBar:AppBar(title:const Text('Заказ · ZAKAZ')),
      body:ListView(padding:const EdgeInsets.all(16),children:[
        Text('Запросить предложение',style:Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height:8),
        Text('Товар ${widget.productId.substring(0,8)} · ${widget.unit}'),
        const SizedBox(height:8),
        const Text('Сначала продавец делает предложение, затем покупатель явно принимает его. Статус PLACED не означает резерв или оплату.'),
        const Divider(height:32),
        if(!BuyerAuth.configured)const Text('OIDC не настроен. Авторизованный заказ недоступен.'),
        if(BuyerAuth.configured && token==null)FilledButton(
          onPressed:busy?null:login,child:const Text('Войти через OIDC / PKCE')),
        if(token!=null && demandId==null) ...[
          TextField(controller:city,decoration:const InputDecoration(labelText:'Город')),
          TextField(controller:quantity,decoration:const InputDecoration(labelText:'Количество'),
                    keyboardType:const TextInputType.numberWithOptions(decimal:true)),
          const SizedBox(height:12),
          FilledButton(onPressed:busy?null:create,child:const Text('Создать BuyerDemand')),
        ],
        if(demandId!=null && orderId==null) ...[
          Text('Заявка: $demandId'),
          OutlinedButton(onPressed:busy?null:refreshBids,child:const Text('Обновить предложения')),
          if(bids.isEmpty)const Text('Пока нет предложений продавцов.'),
          ...bids.map((data){
            final bid=data as Map<String,dynamic>;
            final id=bid['bidId'] as String;
            return Card(child:Padding(padding:const EdgeInsets.all(12),child:Column(
              crossAxisAlignment:CrossAxisAlignment.start,children:[
                Text('Продавец ${(bid['sellerId'] as String).substring(0,8)}'),
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
            Text('Сумма: ${order!['totalAmount']} ${order!['currency']}'),
          ],
        ],
        if(busy)const Center(child:CircularProgressIndicator()),
        if(error!=null)Text(error!,style:const TextStyle(color:Colors.red)),
      ]),
    );
  }
}
