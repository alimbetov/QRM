import 'package:flutter/material.dart';
import 'buyer_auth.dart';
import 'seller_auth.dart';
import 'seller_bid_composer.dart';
import 'zakaz_api.dart';

/// Native seller inbox; AppAuth stays in the external system browser.
class SellerInboxScreen extends StatefulWidget {
  final ZakazApi api;
  final BuyerAuthGateway? authGateway;
  final bool bidWorkspaceEnabled;
  const SellerInboxScreen({required this.api,this.authGateway,
    this.bidWorkspaceEnabled=const bool.fromEnvironment(
      'ZAKAZ_SELLER_BID_WORKSPACE_ENABLED',defaultValue:false),super.key});
  @override State<SellerInboxScreen> createState()=>_SellerInboxScreenState();
}

class _SellerInboxScreenState extends State<SellerInboxScreen> {
  late final BuyerAuthGateway auth;
  BuyerSession? session;
  String? sellerId,error;
  List<dynamic> memberships=[];
  Map<String,dynamic>? orders;
  List<dynamic>? leads,invitations;
  bool busy=false;
  bool? invitationPreference;
  int page=0,tab=0;

  @override void initState(){
    super.initState();
    auth=widget.authGateway??SellerAuth();
  }
  @override void dispose(){session=null;super.dispose();}

  Future<void> action(Future<void> Function() work) async {
    if(busy)return;
    setState((){busy=true;error=null;});
    try {await work();}
    catch(e) {
      if(!mounted)return;
      setState((){
        if(e is ZakazApiError&&e.statusCode==403){
          error='Нет доступа. Проверьте полномочия продавца.';
        }else if(e is ZakazApiError&&e.statusCode==409){
          error='Заказ или заявка уже изменены. Обновите данные.';
        }else{
          error='Не удалось выполнить запрос. Проверьте авторизацию и сеть.';
        }
      });
    }finally{if(mounted)setState(()=>busy=false);}
  }
  Future<void> loadMembershipsDirect() async {
    final current=session;
    if(current==null)throw StateError('Login required');
    final found=await widget.api.sellerAccounts(current.accessToken);
    if(!mounted)return;
    setState((){
      memberships=found;
      sellerId=found.isEmpty?null:found.first['sellerId'] as String;
    });
    if(sellerId!=null)await loadOrdersDirect();
  }
  Future<void> login()=>action(()async {
    final current=await auth.signIn();
    if(!mounted)return;
    setState((){
      session=current;sellerId=null;memberships=[];orders=null;
      leads=null;invitations=null;invitationPreference=null;tab=0;
    });
    await loadMembershipsDirect();
  });
  Future<void> logout()=>action(()async {
    if(session==null)throw StateError('No session');
    await auth.signOut(session!);
    if(mounted)setState((){
      session=null;sellerId=null;memberships=[];orders=null;
      leads=null;invitations=null;invitationPreference=null;tab=0;page=0;
    });
  });
  Future<void> selectSeller(String id)=>action(()async {
    if(!memberships.any((e)=>e['sellerId']==id))
      throw StateError('Unverified seller');
    setState((){
      sellerId=id;orders=null;leads=null;invitations=null;invitationPreference=null;tab=0;page=0;
    });
    await loadOrdersDirect();
  });
  Future<void> loadOrdersDirect() async {
    if(session==null||sellerId==null)throw StateError('Seller not selected');
    final data=await widget.api.sellerOrders(session!.accessToken,sellerId!,page);
    if(mounted)setState(()=>orders=data);
  }
  Future<void> loadOrders()=>action(loadOrdersDirect);
  Future<void> changeOrder(String id,String next) async {
    if(busy)return;
    final ok=await showDialog<bool>(
      context:context,builder:(ctx)=>AlertDialog(
        title:Text(next=='confirm'?'Подтвердить заказ?':'Отменить заказ?'),
        content:Text(next=='confirm'
          ? 'При подтверждении товар будет зарезервирован.'
          : 'Активные резервы будут освобождены.'),
        actions:[
          TextButton(onPressed:()=>Navigator.pop(ctx,false),child:const Text('Назад')),
          FilledButton(onPressed:()=>Navigator.pop(ctx,true),child:const Text('Подтвердить')),
        ]));
    if(ok!=true||!mounted)return;
    await action(()async {
      await widget.api.sellerOrderAction(session!.accessToken,sellerId!,id,next);
      await loadOrdersDirect();
    });
  }
  Future<void> openLeads()=>action(()async {
    setState((){tab=1;leads=null;});
    // Disclosure is audited by Backend; never auto-load contact phones.
    final data=await widget.api.sellerLeads(session!.accessToken,sellerId!);
    if(mounted)setState(()=>leads=data);
  });
  Future<void> markLead(String id,String status) async {
    if(busy)return;
    final ok=await showDialog<bool>(context:context,builder:(ctx)=>AlertDialog(
      title:const Text('Обработать обращение?'),
      content:const Text('Это отметка продавца, а не подтверждение звонка. Изменение необратимо.'),
      actions:[
        TextButton(onPressed:()=>Navigator.pop(ctx,false),child:const Text('Назад')),
        FilledButton(onPressed:()=>Navigator.pop(ctx,true),child:const Text('Продолжить'))
      ]));
    if(ok!=true||!mounted)return;
    await action(()async {
      await widget.api.sellerLeadStatus(session!.accessToken,sellerId!,id,status);
      final data=await widget.api.sellerLeads(session!.accessToken,sellerId!);
      if(mounted)setState(()=>leads=data);
    });
  }
  Future<void> openInvitations()=>action(()async {
    setState((){tab=2;invitations=null;invitationPreference=null;});
    final token=session!.accessToken;
    final seller=sellerId!;
    if(widget.bidWorkspaceEnabled){
      final preference=await widget.api.sellerInvitationPreference(token,seller);
      if(mounted)setState(()=>invitationPreference=preference);
    }
    final data=await widget.api.sellerInvitations(token,seller);
    if(mounted)setState(()=>invitations=data);
  });

  Future<void> setInvitationPreference(bool enabled)=>action(()async {
    await widget.api.setSellerInvitationPreference(
      session!.accessToken,sellerId!,enabled);
    final state=await widget.api.sellerInvitationPreference(
      session!.accessToken,sellerId!);
    final data=await widget.api.sellerInvitations(session!.accessToken,sellerId!);
    if(mounted)setState((){
      invitationPreference=state;invitations=data;
    });
  });

  Widget orderTile(Map<String,dynamic> o){
    final status=o['status'] as String;
    final id=o['orderId'] as String;
    final items=o['lines'] as List<dynamic>;
    return Card(child:Padding(padding:const EdgeInsets.all(12),child:Column(
      crossAxisAlignment:CrossAxisAlignment.start,children:[
        Text('Заказ '+(o['publicNumber'] as String)+' · '+status,
          style:Theme.of(context).textTheme.titleMedium),
        Text('Итого '+(o['totalAmount'] as String)+' '+(o['currency'] as String)),
        ...items.map((row){
          final l=row as Map<String,dynamic>;
          final code=l['skuId'] as String;
          return Text('SKU '+code.substring(0,8)+' · '+(l['quantity'] as String)+
            ' '+(l['unit'] as String));
        }),
        if(status=='PLACED')FilledButton(
          onPressed:busy?null:()=>changeOrder(id,'confirm'),
          child:const Text('Подтвердить и зарезервировать')),
        if(status=='PLACED'||status=='CONFIRMED')OutlinedButton(
          onPressed:busy?null:()=>changeOrder(id,'cancel'),
          child:const Text('Отменить заказ')),
      ],
    )));
  }
  Widget leadTile(Map<String,dynamic> l){
    final status=l['status'] as String;
    final id=l['leadId'] as String;
    return Card(child:Padding(padding:const EdgeInsets.all(12),child:Column(
      crossAxisAlignment:CrossAxisAlignment.start,children:[
        Text('Заявка на звонок · '+status),
        Text('Телефон: '+(l['phone'] as String)),
        if(status=='NEW')Wrap(spacing:8,children:[
          OutlinedButton(onPressed:busy?null:()=>markLead(id,'CONTACTED'),
            child:const Text('Отметить «Связались»')),
          OutlinedButton(onPressed:busy?null:()=>markLead(id,'DECLINED'),
            child:const Text('Отклонить заявку')),
        ]),
      ],
    )));
  }

  @override Widget build(BuildContext context){
    final rows=orders?['items'] as List<dynamic>? ?? [];
    return Scaffold(appBar:AppBar(title:const Text('ZAKAZ · продавец'),
      actions:[if(session!=null)IconButton(
        tooltip:'Выйти из аккаунта продавца',onPressed:busy?null:logout,
        icon:const Icon(Icons.logout))]),
      body:ListView(padding:const EdgeInsets.all(16),children:[
        if(!auth.isConfigured)
          const Text('Отдельный OIDC-клиент продавца не настроен.'),
        if(auth.isConfigured&&session==null)FilledButton(
          onPressed:busy?null:login,child:const Text('Войти как продавец')),
        if(session!=null&&memberships.isEmpty)
          OutlinedButton(onPressed:busy?null:()=>action(loadMembershipsDirect),
            child:const Text('Повторить проверку организаций')),
        if(memberships.length>1)DropdownButton<String>(
          value:sellerId,isExpanded:true,
          items:memberships.map((s)=>DropdownMenuItem<String>(
            value:s['sellerId'] as String,child:Text(s['sellerId'] as String))).toList(),
          onChanged:busy?null:(id){if(id!=null)selectSeller(id);}),
        if(sellerId!=null)...[
          Wrap(spacing:8,runSpacing:8,children:[
            OutlinedButton(onPressed:busy?null:()=>action(()async {
              setState(()=>tab=0);await loadOrdersDirect();
            }),child:const Text('Заказы')),
            OutlinedButton(onPressed:busy?null:openLeads,
              child:const Text('Обратные звонки')),
            OutlinedButton(onPressed:busy?null:openInvitations,
              child:const Text('Приглашения')),
          ]),
          const SizedBox(height:12),
          if(tab==0)...[
            OutlinedButton(onPressed:busy?null:loadOrders,
              child:const Text('Обновить заказы')),
            if(orders!=null&&rows.isEmpty)const Text('Заказов пока нет.'),
            ...rows.map((r)=>orderTile(r as Map<String,dynamic>)),
            if(orders!=null)Row(mainAxisAlignment:MainAxisAlignment.center,children:[
              IconButton(tooltip:'Предыдущая страница',icon:const Icon(Icons.chevron_left),
                onPressed:busy||page==0?null:(){page--;loadOrders();}),
              Text('Страница '+(page+1).toString()+' · всего '+orders!['total'].toString()),
              IconButton(tooltip:'Следующая страница',icon:const Icon(Icons.chevron_right),
                onPressed:busy||(page+1)*20>=(orders!['total'] as int)?null:
                  (){page++;loadOrders();}),
            ]),
          ],
          if(tab==1)...[
            const Text('Каждое открытие номера фиксируется в журнале доступа.'),
            if(leads!=null&&leads!.isEmpty)const Text('Обращений пока нет.'),
            ...?leads?.map((l)=>leadTile(l as Map<String,dynamic>)),
          ],
          if(tab==2)...[
            const Text('Приглашения с согласиями. Без автоматических SMS/Push.'),
            if(widget.bidWorkspaceEnabled && invitationPreference!=null)
              SwitchListTile(
                value:invitationPreference!,
                title:const Text('Получать приглашения от покупателей'),
                subtitle:const Text('Настройка хранится на сервере и выключена по умолчанию.'),
                onChanged:busy?null:setInvitationPreference),
            if(invitations!=null&&invitations!.isEmpty)
              const Text('Приглашений пока нет.'),
            ...?invitations?.map((item){
              final i=item as Map<String,dynamic>;
              return ListTile(title:Text('Demand: '+(i['demandId'] as String)),
                subtitle:Text('Получено: '+(i['deliveredAt'] as String)),
                trailing:widget.bidWorkspaceEnabled?OutlinedButton(
                  onPressed:busy?null:(){
                    Navigator.of(context).push(MaterialPageRoute<void>(
                      builder:(_)=>SellerBidComposer(api:widget.api,
                        token:session!.accessToken,sellerId:sellerId!,
                        invitationId:i['invitationId'] as String)));
                  },
                  child:const Text('Ответить ставкой')):null);
            }),
          ],
        ],
        if(busy)const Center(child:CircularProgressIndicator()),
        if(error!=null)Text(error!,
          style:TextStyle(color:Theme.of(context).colorScheme.error)),
      ]),
    );
  }
}
