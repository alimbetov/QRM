import 'package:flutter/material.dart';
import 'zakaz_api.dart';

/// Native bid composer. Seller token is never embedded in a WebView.
class SellerBidComposer extends StatefulWidget {
  final ZakazApi api;
  final String token,sellerId,invitationId;
  const SellerBidComposer({required this.api,required this.token,
    required this.sellerId,required this.invitationId,super.key});
  @override State<SellerBidComposer> createState()=>_SellerBidComposerState();
}
class _SellerBidComposerState extends State<SellerBidComposer> {
  Map<String,dynamic>? quote;
  final Map<String,String> selected={};
  final Map<String,TextEditingController> prices={};
  final fee=TextEditingController(text:'0.00');
  String? origin,error,receipt;
  String method='PICKUP';
  bool busy=false;
  static final pricePattern=RegExp(r'^(?:0|[1-9]\d{0,16})(?:\.\d{1,2})?$');
  static final qtyPattern=RegExp(r'^(?:0|[1-9]\d{0,15})(?:\.\d{1,3})?$');
  @override void initState(){super.initState();load();}
  @override void dispose(){
    for(final c in prices.values){c.dispose();}
    fee.dispose();super.dispose();
  }

  Future<void> load() async {
    if(busy)return;
    setState((){busy=true;error=null;receipt=null;quote=null;});
    try {
      final data=await widget.api.sellerBidContext(
        widget.token,widget.sellerId,widget.invitationId);
      if(!mounted)return;
      for(final c in prices.values){c.dispose();}
      prices.clear();selected.clear();
      for(final raw in data['lines'] as List<dynamic>){
        final line=raw as Map<String,dynamic>;
        final product=line['productId'] as String;
        final first=(line['skus'] as List).first as Map<String,dynamic>;
        selected[product]=first['skuId'] as String;
        prices[product]=TextEditingController(text:first['priceKzt'] as String? ?? '');
      }
      final locations=data['origins'] as List<dynamic>;
      setState((){
        quote=data;
        origin=locations.isEmpty?null:
          (locations.first as Map<String,dynamic>)['originLocationId'] as String;
        method='PICKUP';fee.text='0.00';
      });
    }catch(e){
      if(mounted)setState(()=>error=e is ZakazApiError
        ? 'Запрос недоступен: HTTP '+e.statusCode.toString()
        : 'Не удалось загрузить запрос. Проверьте права и сеть.');
    }finally{if(mounted)setState(()=>busy=false);}
  }
  static BigInt? decimal(String value,int scale) {
    if(!(scale==2?pricePattern:qtyPattern).hasMatch(value))return null;
    final parts=value.split('.');
    final fraction=(parts.length>1?parts[1]:'').padRight(scale,'0');
    return BigInt.parse(parts.first)*BigInt.from(scale==2?100:1000)+
      BigInt.parse(fraction);
  }
  static String formatMoney(BigInt cents)=>
    (cents~/BigInt.from(100)).toString()+'.'+
    (cents%BigInt.from(100)).toString().padLeft(2,'0');
  List<dynamic> get lines=>quote?['lines'] as List<dynamic>? ?? [];
  List<dynamic> get origins=>quote?['origins'] as List<dynamic>? ?? [];
  Map<String,dynamic>? chosen(Map<String,dynamic> line) {
    for(final raw in line['skus'] as List<dynamic>){
      final item=raw as Map<String,dynamic>;
      if(item['skuId']==selected[line['productId']])return item;
    }
    return null;
  }
  BigInt? totalCents() {
    if(quote==null||origin==null)return null;
    final feeCents=decimal(fee.text.trim(),2);
    if(feeCents==null || (method=='PICKUP'&&feeCents!=BigInt.zero))return null;
    var total=feeCents;
    for(final raw in lines){
      final line=raw as Map<String,dynamic>;
      final item=chosen(line);
      if(item==null)return null;
      final units=decimal(line['quantity'] as String,3);
      final amount=decimal(prices[line['productId']]?.text.trim()??'',2);
      final stock=(item['availableByOrigin'] as Map<String,dynamic>)[origin];
      final available=decimal(stock is String?stock:'0',3);
      if(units==null||units<=BigInt.zero||amount==null||
         available==null||available<units)return null;
      total+=((amount*units)+BigInt.from(500))~/BigInt.from(1000);
    }
    return total;
  }
  bool get allowed {
    if(quote==null||busy||receipt!=null)return false;
    final originsAllowed=origins.any((raw){
      final o=raw as Map<String,dynamic>;
      return o['originLocationId']==origin &&
        (method=='PICKUP'?o['pickup']==true:o['delivery']==true);
    });
    return originsAllowed&&totalCents()!=null;
  }

  Future<void> submit() async {
    if(!allowed)return;
    final cost=formatMoney(totalCents()!);
    final yes=await showDialog<bool>(context:context,builder:(ctx)=>AlertDialog(
      title:const Text('Отправить предложение?'),
      content:Text('Итого '+cost+' KZT с доставкой. '
        'Покупатель принимает предложение отдельно. Резерв пока не создаётся.'),
      actions:[
        TextButton(onPressed:()=>Navigator.pop(ctx,false),child:const Text('Назад')),
        FilledButton(onPressed:()=>Navigator.pop(ctx,true),child:const Text('Отправить'))]));
    if(yes!=true||!mounted)return;
    final now=DateTime.now().toUtc();
    final demandExpiry=DateTime.parse(quote!['expiresAt'] as String).toUtc();
    final soon=now.add(const Duration(minutes:15));
    final expiry=(soon.isBefore(demandExpiry)?soon:demandExpiry)
      .subtract(const Duration(seconds:1));
    if(!expiry.isAfter(now)){
      setState(()=>error='Срок запроса истёк.');return;
    }
    final rows=lines.map((raw){
      final line=raw as Map<String,dynamic>;
      return <String,dynamic>{
        'productId':line['productId'],
        'skuId':selected[line['productId']],
        'quantity':line['quantity'],
        'unitPrice':prices[line['productId']]!.text.trim()
      };
    }).toList();
    setState((){busy=true;error=null;});
    try{
      final id=await widget.api.sellerSubmitInvitationBid(widget.token,
        widget.sellerId,widget.invitationId,{
          'revision':quote!['nextRevision'],'expiresAt':expiry.toIso8601String(),
          'method':method,'originLocationId':origin,
          'deliveryFee':fee.text.trim(),'lines':rows
        });
      if(mounted)setState(()=>receipt=id);
    }catch(e){
      if(mounted)setState(()=>error=e is ZakazApiError
        ? 'Ставка отклонена: HTTP '+e.statusCode.toString()+
          '. Обновите запрос и проверьте остатки.'
        : 'Ставка не отправлена. Проверьте соединение.');
    }finally{if(mounted)setState(()=>busy=false);}
  }

  @override Widget build(BuildContext context) {
    final total=totalCents();
    return Scaffold(appBar:AppBar(title:const Text('Ответить ставкой')),
      body:ListView(padding:const EdgeInsets.all(16),children:[
        if(busy)const LinearProgressIndicator(),
        if(error!=null)Text(error!,
          style:TextStyle(color:Theme.of(context).colorScheme.error)),
        if(receipt!=null)...[
          const Text('Предложение отправлено. Покупатель может его принять.'),
          SelectableText('Bid ID: '+receipt!)
        ],
        if(quote==null)
          OutlinedButton(onPressed:busy?null:load,child:const Text('Обновить запрос')),
        if(quote!=null)...[
          Text('Город: '+(quote!['destinationCity'] as String)),
          Text('Ревизия: '+quote!['nextRevision'].toString()),
          const Text('Остаток предварительный; резерв создаётся при подтверждении заказа.'),
          ...lines.map((raw){
            final line=raw as Map<String,dynamic>;
            final product=line['productId'] as String;
            final options=line['skus'] as List<dynamic>;
            final item=chosen(line);
            final stock=(item?['availableByOrigin'] as Map<String,dynamic>?)?[origin];
            return Card(child:Padding(padding:const EdgeInsets.all(12),child:Column(
              crossAxisAlignment:CrossAxisAlignment.start,children:[
                Text(line['productName'] as String,
                  style:Theme.of(context).textTheme.titleMedium),
                Text('Количество '+line['quantity'].toString()+' '+line['unit'].toString()),
                DropdownButton<String>(isExpanded:true,value:selected[product],
                  items:options.map((raw){
                    final opt=raw as Map<String,dynamic>;
                    return DropdownMenuItem<String>(value:opt['skuId'] as String,
                      child:Text(opt['code'] as String));
                  }).toList(),
                  onChanged:busy||receipt!=null?null:(id){
                    if(id==null)return;
                    setState((){
                      selected[product]=id;
                      prices[product]!.text=chosen(line)?['priceKzt'] as String? ?? '';
                    });
                  }),
                Text('Доступно: '+(stock?.toString()??'0')),
                TextField(controller:prices[product],enabled:!busy&&receipt==null,
                  decoration:const InputDecoration(labelText:'Цена за единицу, KZT'),
                  keyboardType:const TextInputType.numberWithOptions(decimal:true),
                  onChanged:(_)=>setState((){}))
              ])));
          }),
          DropdownButton<String>(isExpanded:true,value:origin,
            hint:const Text('Выберите склад'),
            items:origins.map((raw){
              final o=raw as Map<String,dynamic>;
              return DropdownMenuItem<String>(value:o['originLocationId'] as String,
                child:Text(o['city'].toString()+' · '+o['address'].toString()));
            }).toList(),onChanged:busy||receipt!=null?null:
              (id)=>setState(()=>origin=id)),
          DropdownButton<String>(value:method,items:const [
            DropdownMenuItem(value:'PICKUP',child:Text('Самовывоз')),
            DropdownMenuItem(value:'DELIVERY',child:Text('Доставка'))
          ],onChanged:busy||receipt!=null?null:(v){
            if(v!=null)setState((){
              method=v;
              if(v=='PICKUP')fee.text='0.00';
            });
          }),
          TextField(controller:fee,enabled:!busy&&receipt==null&&method=='DELIVERY',
            decoration:const InputDecoration(labelText:'Доставка, KZT'),
            keyboardType:const TextInputType.numberWithOptions(decimal:true),
            onChanged:(_)=>setState((){})),
          Text('Итого с доставкой: '+
            (total==null?'недоступно':formatMoney(total))+' KZT'),
          FilledButton(onPressed:allowed?submit:null,
            child:const Text('Отправить SellerBid')),
          OutlinedButton(onPressed:busy?null:load,child:const Text('Обновить запрос'))
        ]
      ]));
  }
}
