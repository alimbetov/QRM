import 'dart:convert';
import 'dart:math';
import 'package:flutter/material.dart';
import 'zakaz_api.dart';
import 'mobile_validation.dart';

class QrScreen extends StatefulWidget {
  final ZakazApi api;
  final String token;
  const QrScreen({required this.api,required this.token,super.key});
  @override State<QrScreen> createState()=>_QrScreenState();
}

class _QrScreenState extends State<QrScreen> {
  final phone=TextEditingController();
  late Future<Map<String,dynamic>> listing;
  bool consent=false,busy=false,accepted=false;
  String? error;
  String? key;

  @override void initState() {
    super.initState();
    listing=widget.api.qr(widget.token);
  }

  @override void didUpdateWidget(covariant QrScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if(oldWidget.token!=widget.token || oldWidget.api!=widget.api) {
      listing=widget.api.qr(widget.token);
      phone.clear();
      consent=false;
      accepted=false;
      busy=false;
      error=null;
      key=null;
    }
  }

  @override void dispose() {phone.dispose();super.dispose();}

  void _reload() {
    setState(()=>listing=widget.api.qr(widget.token));
  }

  String nonce() {
    final random=Random.secure();
    return base64UrlEncode(List<int>.generate(18,(_)=>random.nextInt(256)))
      .replaceAll('=','');
  }

  Future<void> submit() async {
    if(busy || accepted)return;
    if(!consent) {
      setState(()=>error='Подтвердите согласие на обработку номера.');
      return;
    }
    if(!validContactPhone(phone.text)) {
      setState(()=>error='Укажите номер в формате +77001234567.');
      return;
    }
    key ??= nonce(); // Preserve the key across network retries for the same phone.
    setState((){busy=true;error=null;});
    try {
      await widget.api.lead(widget.token,phone.text.trim(),key!);
      if(mounted)setState(()=>accepted=true);
    } catch (_) {
      if(mounted)setState(()=>error='Обращение не принято. Проверьте сеть и повторите.');
    } finally {
      if(mounted)setState(()=>busy=false);
    }
  }

  @override Widget build(BuildContext context)=>Scaffold(
    appBar:AppBar(title:const Text('Карточка по QR')),
    body:FutureBuilder<Map<String,dynamic>>(future:listing,builder:(context,snapshot){
      if(snapshot.connectionState!=ConnectionState.done) {
        return const Center(child:CircularProgressIndicator());
      }
      if(snapshot.hasError) {
        return Center(child:Column(mainAxisSize:MainAxisSize.min,children:[
          const Text('Предложение недоступно. Проверьте сеть или QR-код.'),
          const SizedBox(height:12),
          OutlinedButton.icon(
            onPressed:_reload,icon:const Icon(Icons.refresh),
            label:const Text('Повторить загрузку')),
        ]));
      }
      final data=snapshot.data!;
      final variants=(data['variants'] as List<dynamic>).cast<Map<String,dynamic>>();
      return ListView(padding:const EdgeInsets.all(16),children:[
        Text(data['title'] as String,
          style:Theme.of(context).textTheme.headlineSmall),
        Text(data['category'] as String? ?? ''),
        const SizedBox(height:12),
        if(variants.isEmpty)const Text('Варианты уточняются у продавца'),
        ...variants.map((item)=>ListTile(
          title:Text(item['code'] as String),
          subtitle:Text(item['unit'] as String? ?? 'Единица уточняется'),
          trailing:Text(formatPublishedPriceKzt(item['priceKzt'])))),
        const Divider(height:36),
        const Text('Оставить номер для обратного звонка. Это не покупка и не создание аккаунта.'),
        TextField(controller:phone,
          decoration:const InputDecoration(labelText:'Телефон E.164 (+7...)'),
          keyboardType:TextInputType.phone,
          onChanged:(_){key=null;}),
        CheckboxListTile(value:consent,
          onChanged:busy||accepted?null:(value)=>setState(()=>consent=value??false),
          title:const Text('Согласен на обработку номера для ответа продавца')),
        if(accepted)const Text('Обращение принято, заказ не создан.'),
        if(error!=null)
          Text(error!,style:TextStyle(color:Theme.of(context).colorScheme.error)),
        FilledButton(onPressed:busy||accepted?null:submit,
          child:Text(busy?'Отправляем…':'Отправить обращение')),
      ]);
    }),
  );
}
