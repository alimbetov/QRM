import 'package:flutter/material.dart';
import 'zakaz_api.dart';
import 'qr_screen.dart';
import 'buyer_screen.dart';

class CatalogScreen extends StatefulWidget {
  final ZakazApi api;
  const CatalogScreen({required this.api,super.key});
  @override State<CatalogScreen> createState()=>_CatalogScreenState();
}

class _CatalogScreenState extends State<CatalogScreen> {
  final search=TextEditingController();
  late Future<Map<String,dynamic>> results;
  int page=0;
  @override void initState(){super.initState();_refresh();}
  void _refresh(){setState(()=>results=widget.api.catalog(query:search.text.trim(),page:page));}
  @override void dispose(){search.dispose();widget.api.dispose();super.dispose();}
  String price(dynamic input) => input==null?'Цена уточняется':'$input ₸';

  @override Widget build(BuildContext context) {
    return Scaffold(
      appBar:AppBar(title:const Text('ZAKAZ · каталог')),
      body:Padding(padding:const EdgeInsets.all(16),child:Column(children:[
        TextField(controller:search,
          decoration:InputDecoration(labelText:'Поиск по товарам',
            suffixIcon:IconButton(icon:const Icon(Icons.search),onPressed:(){
              page=0;_refresh();
            })),
          onSubmitted:(_){page=0;_refresh();}),
        const SizedBox(height:12),
        Expanded(child:FutureBuilder<Map<String,dynamic>>(
          future:results,builder:(context,snapshot){
            if(snapshot.connectionState!=ConnectionState.done){
              return const Center(child:CircularProgressIndicator());
            }
            if(snapshot.hasError) return const Center(child:Text(
              'Не удалось получить каталог. Проверьте Backend и соединение.'));
            final data=snapshot.data!;
            final items=(data['items'] as List<dynamic>? ?? []);
            if(items.isEmpty)return const Center(child:Text('Опубликованные объявления не найдены.'));
            return ListView.builder(itemCount:items.length,itemBuilder:(context,index){
              final item=items[index] as Map<String,dynamic>;
              final variants=(item['variants'] as List<dynamic>? ?? []);
              final first=variants.isEmpty?null:variants.first as Map<String,dynamic>;
              final token=item['qrToken'] as String? ?? '';
              final product=item['productId'] as String? ?? '';
              final unit=first?['unit'] as String? ?? '';
              return Card(child:Padding(padding:const EdgeInsets.all(12),child:Column(
                crossAxisAlignment:CrossAxisAlignment.start,children:[
                  Text(item['category'] as String? ?? 'Категория',
                    style:Theme.of(context).textTheme.labelMedium),
                  Text(item['title'] as String? ?? 'Без названия',
                    style:Theme.of(context).textTheme.titleLarge),
                  const SizedBox(height:8),
                  Text(first==null?'Без вариантов':price(first['priceKzt'])),
                  const SizedBox(height:8),
                  Wrap(spacing:8,children:[
                    if(token.isNotEmpty)OutLinedQrButton(onPressed:(){
                      Navigator.push(context,MaterialPageRoute(
                        builder:(_)=>QrScreen(api:widget.api,token:token)));
                    }),
                    if(item['productType']=='PHYSICAL' && product.isNotEmpty &&
                        unit.isNotEmpty && unit!='SERVICE' && unit!='LICENSE')
                      FilledButton(onPressed:(){
                        Navigator.push(context,MaterialPageRoute(builder:(_)=>
                          BuyerScreen(api:widget.api,productId:product,unit:unit)));
                      },child:const Text('Запросить цену')),
                  ]),
                ])));
            });
          })),
        FutureBuilder<Map<String,dynamic>>(future:results,builder:(context,snapshot){
          final total=(snapshot.data?['total'] as num?)?.toInt()??0;
          return Row(mainAxisAlignment:MainAxisAlignment.center,children:[
            IconButton(onPressed:page==0?null:(){page--;_refresh();},
              icon:const Icon(Icons.chevron_left)),
            Text('Страница ${page+1} · $total предложений'),
            IconButton(onPressed:((page+1)*12>=total)?null:(){page++;_refresh();},
              icon:const Icon(Icons.chevron_right)),
          ]);
        })
      ])),
    );
  }
}

class OutLinedQrButton extends StatelessWidget {
  final VoidCallback onPressed;
  const OutLinedQrButton({required this.onPressed,super.key});
  @override Widget build(BuildContext context)=>OutlinedButton(
    onPressed:onPressed,child:const Text('QR-карточка'));
}
