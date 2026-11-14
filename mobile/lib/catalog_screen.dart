import 'package:flutter/material.dart';
import 'zakaz_api.dart';
import 'mobile_validation.dart';
import 'qr_screen.dart';
import 'buyer_screen.dart';
import 'seller_inbox_screen.dart';

/// Public discovery stays usable without login; no synthetic items on API errors.
class CatalogScreen extends StatefulWidget {
  final ZakazApi api;
  const CatalogScreen({required this.api,super.key});
  @override State<CatalogScreen> createState()=>_CatalogScreenState();
}

class _CatalogScreenState extends State<CatalogScreen> {
  final search=TextEditingController();
  late Future<Map<String,dynamic>> results;
  int page=0;

  @override void initState() {
    super.initState();
    results=widget.api.catalog();
  }

  void _refresh({bool resetPage=false}) {
    setState(() {
      if(resetPage) page=0;
      results=widget.api.catalog(query:search.text.trim(),page:page);
    });
  }

  @override void dispose() {
    search.dispose();
    widget.api.dispose();
    super.dispose();
  }

  void _openBuyer(String productId,String unit) {
    Navigator.push(context,MaterialPageRoute<void>(
      builder:(_)=>BuyerScreen(api:widget.api,productId:productId,unit:unit)));
  }

  Widget _card(Map<String,dynamic> item) {
    final variants=(item['variants'] as List<dynamic>).cast<Map<String,dynamic>>();
    final token=item['qrToken'] as String;
    final product=item['productId'] as String;
    final physical=item['productType']=='PHYSICAL';
    // Demand is for a canonical product and unit, NOT for a chosen seller SKU.
    final eligibleUnits=<String>{};
    if(physical) {
      for(final variant in variants) {
        final unit=variant['unit'];
        if(unit is String && unit.isNotEmpty && unit!='SERVICE' && unit!='LICENSE') {
          eligibleUnits.add(unit);
        }
      }
    }
    return Card(child:Padding(padding:const EdgeInsets.all(12),
      child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
        Text(item['category'] as String? ?? 'Категория',
          style:Theme.of(context).textTheme.labelMedium),
        Text(item['title'] as String,
          style:Theme.of(context).textTheme.titleLarge),
        const SizedBox(height:8),
        if(variants.isEmpty)const Text('Варианты уточняются у продавца'),
        ...variants.take(5).map((variant)=>Padding(
          padding:const EdgeInsets.only(bottom:4),
          child:Text((variant['code'] as String)+' · '+
            (variant['unit'] as String? ?? 'единица не указана')+' · '+
            formatPublishedPriceKzt(variant['priceKzt'])))),
        if(variants.length>5)
          Text('Другие варианты: '+(variants.length-5).toString()+' (откройте QR-карточку)'),
        const SizedBox(height:8),
        Wrap(spacing:8,runSpacing:8,children:[
          OutlinedButton(onPressed:(){
            Navigator.push(context,MaterialPageRoute<void>(
              builder:(_)=>QrScreen(api:widget.api,token:token)));
          },child:const Text('QR-карточка')),
          ...eligibleUnits.map((unit)=>FilledButton(
            onPressed:()=>_openBuyer(product,unit),
            child:Text('Запросить предложение · '+unit))),
        ]),
      ])));
  }

  @override Widget build(BuildContext context) {
    return Scaffold(
      appBar:AppBar(title:const Text('ZAKAZ · каталог'),
        actions:[
          if(const bool.fromEnvironment('ZAKAZ_SELLER_STUDIO_ENABLED'))
            IconButton(tooltip:'Кабинет продавца',icon:const Icon(Icons.store),
              onPressed:()=>Navigator.push(context,MaterialPageRoute<void>(
                builder:(_)=>SellerInboxScreen(api:widget.api)))),
        ]),
      body:Padding(padding:const EdgeInsets.all(16),child:Column(children:[
        TextField(controller:search,
          decoration:InputDecoration(labelText:'Поиск по товарам',
            suffixIcon:IconButton(tooltip:'Найти',icon:const Icon(Icons.search),
              onPressed:()=>_refresh(resetPage:true))),
          onSubmitted:(_)=>_refresh(resetPage:true)),
        const SizedBox(height:12),
        Expanded(child:FutureBuilder<Map<String,dynamic>>(
          future:results,builder:(context,snapshot){
            if(snapshot.connectionState!=ConnectionState.done) {
              return const Center(child:CircularProgressIndicator());
            }
            if(snapshot.hasError) {
              return Center(child:Column(mainAxisSize:MainAxisSize.min,children:[
                const Text('Не удалось загрузить каталог. Проверьте соединение.'),
                const SizedBox(height:12),
                OutlinedButton.icon(
                  onPressed:_refresh,
                  icon:const Icon(Icons.refresh),
                  label:const Text('Повторить загрузку')),
              ]));
            }
            final data=snapshot.data!;
            final items=(data['items'] as List<dynamic>);
            final total=data['total'] as int;
            return Column(children:[
              Expanded(child:items.isEmpty
                ? const Center(child:Text('Опубликованные объявления не найдены.'))
                : ListView.builder(itemCount:items.length,
                    itemBuilder:(_,index)=>_card(items[index] as Map<String,dynamic>))),
              Row(mainAxisAlignment:MainAxisAlignment.center,children:[
                IconButton(tooltip:'Предыдущая страница',
                  onPressed:page==0?null:(){page--;_refresh();},
                  icon:const Icon(Icons.chevron_left)),
                Flexible(child:Text('Страница '+(page+1).toString()+
                  ' · '+total.toString()+' предложений',
                  textAlign:TextAlign.center)),
                IconButton(tooltip:'Следующая страница',
                  onPressed:(page+1)*12>=total?null:(){page++;_refresh();},
                  icon:const Icon(Icons.chevron_right)),
              ]),
            ]);
          })),
      ])),
    );
  }
}
