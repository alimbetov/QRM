import 'dart:convert';
import 'dart:math';
import 'package:flutter/material.dart';
import 'zakaz_api.dart';

class QrScreen extends StatefulWidget {
  final ZakazApi api;
  final String token;
  const QrScreen({required this.api,required this.token,super.key});
  @override State<QrScreen> createState()=>_QrScreenState();
}

class _QrScreenState extends State<QrScreen> {
  final phone=TextEditingController();
  late final Future<Map<String,dynamic>> listing;
  bool consent=false,busy=false,accepted=false;
  String? error;
  String? key;
  @override void initState(){super.initState();listing=widget.api.qr(widget.token);}
  @override void dispose(){phone.dispose();super.dispose();}
  String nonce(){
    final random=Random.secure();
    return base64UrlEncode(List<int>.generate(18,(_)=>random.nextInt(256))).replaceAll('=','');
  }
  Future<void> submit() async {
    if(busy || !consent || !RegExp(r'^\+[1-9][0-9]{7,14}$').hasMatch(phone.text.trim())) return;
    key ??= nonce();
    setState((){busy=true;error=null;});
    try {
      await widget.api.lead(widget.token,phone.text.trim(),key!);
      if(mounted)setState(()=>accepted=true);
    } catch (_) {
      if(mounted)setState(()=>error='Обращение не принято. Повторите позже.');
    } finally {if(mounted)setState(()=>busy=false);}
  }

  @override Widget build(BuildContext context)=>Scaffold(
    appBar:AppBar(title:const Text('Карточка по QR')),
    body:FutureBuilder<Map<String,dynamic>>(future:listing,builder:(context,snapshot){
      if(!snapshot.hasData){
        return Center(child:Text(snapshot.hasError
          ?'Предложение недоступно':'Загружаем карточку…'));
      }
      final data=snapshot.data!;
      final variants=(data['variants'] as List<dynamic>? ?? []);
      return ListView(padding:const EdgeInsets.all(16),children:[
        Text(data['title'] as String? ?? 'Предложение',
            style:Theme.of(context).textTheme.headlineSmall),
        Text(data['category'] as String? ?? ''),
        const SizedBox(height:12),
        ...variants.map((v){
          final item=v as Map<String,dynamic>;
          final price=item['priceKzt'];
          return ListTile(title:Text(item['code'] as String? ?? ''),
            subtitle:Text(item['unit'] as String? ?? 'Единица уточняется'),
            trailing:Text(price==null?'Цена уточняется':'$price ₸'));
        }),
        const Divider(height:36),
        const Text('Оставить номер для обратного звонка. Это не покупка и не создание аккаунта.'),
        TextField(controller:phone,
          decoration:const InputDecoration(labelText:'Телефон E.164 (+7...)'),
          keyboardType:TextInputType.phone,onChanged:(_){key=null;}),
        CheckboxListTile(value:consent,
          onChanged:(value)=>setState(()=>consent=value??false),
          title:const Text('Согласен на обработку номера для ответа продавца')),
        if(accepted)const Text('Обращение принято, заказ не создан.'),
        if(error!=null)Text(error!,style:const TextStyle(color:Colors.red)),
        FilledButton(onPressed:busy||accepted?null:submit,
          child:Text(busy?'Отправляем…':'Отправить обращение')),
      ]);
    }),
  );
}
