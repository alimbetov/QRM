import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'mobile_validation.dart';

/// A denied or failed request never produces a fabricated buyer/order object.
class ZakazApiError extends StateError {
  final int statusCode;
  ZakazApiError(this.statusCode):super('API HTTP $statusCode');
}

class ZakazApi {
  final Uri origin;
  final http.Client client;
  final Duration requestTimeout;

  ZakazApi(String baseUrl, {
    http.Client? client,
    this.requestTimeout = const Duration(seconds: 20),
  }) : origin = Uri.parse(baseUrl),
       client = client ?? http.Client() {
    final loopback = {'localhost', '127.0.0.1', '10.0.2.2'};
    final secure = origin.scheme == 'https';
    final emulator = origin.scheme == 'http' && loopback.contains(origin.host);
    if (origin.host.isEmpty || !(secure || emulator) ||
        (origin.path.isNotEmpty && origin.path != '/') ||
        origin.userInfo.isNotEmpty || origin.hasQuery || origin.hasFragment ||
        requestTimeout <= Duration.zero) {
      throw ArgumentError('HTTPS root API URL required outside local emulator');
    }
  }

  Uri url(String path, [Map<String,String>? params]) {
    if (!path.startsWith('/') || path.startsWith('//')) {
      throw ArgumentError('API path must be relative to the configured origin');
    }
    final uri = origin.resolve(path);
    if (uri.scheme != origin.scheme || uri.host != origin.host ||
        uri.port != origin.port) {
      throw ArgumentError('Cross-origin API path rejected');
    }
    return params == null ? uri : uri.replace(queryParameters: params);
  }

  static String _requireToken(String token) {
    if (token.trim().isEmpty) throw ArgumentError('Buyer access token required');
    return token;
  }

  static String _requireKey(String key) {
    if (!RegExp(r'^[A-Za-z0-9_.:-]{1,80}$').hasMatch(key)) {
      throw ArgumentError('Invalid idempotency key');
    }
    return key;
  }

  static String _nonempty(Map<String,dynamic> data,String field) {
    final value=data[field];
    if (value is! String || value.isEmpty) {
      throw FormatException('Invalid API response field: $field');
    }
    return value;
  }

  static List<dynamic> _array(Map<String,dynamic> data,String field) {
    final value=data[field];
    if (value is! List<dynamic>) {
      throw FormatException('Invalid API response field: $field');
    }
    return value;
  }

  static void _catalogItem(dynamic value) {
    if (value is! Map<String,dynamic>) {
      throw const FormatException('Invalid catalog item');
    }
    for (final name in ['listingId','productId','qrToken','title','productType']) {
      _nonempty(value,name);
    }
    if (!validQrToken(value['qrToken'] as String)) {
      throw const FormatException('Invalid catalog QR token');
    }
    for (final raw in _array(value,'variants')) {
      _variant(raw);
    }
  }

  static void _variant(dynamic value) {
    if (value is! Map<String,dynamic>) {
      throw const FormatException('Invalid SKU variant');
    }
    _nonempty(value,'id');
    _nonempty(value,'code');
    final unit=value['unit'];
    final price=value['priceKzt'];
    if ((unit != null && unit is! String) ||
        (price != null && price is! String)) {
      throw const FormatException('Invalid SKU unit or price');
    }
  }

  static void _profile(Map<String,dynamic> data) {
    _nonempty(data,'buyerId');
    _nonempty(data,'nickname');
    _nonempty(data,'email');
    if (data['maskedPhone'] is! String || data['phoneVerified'] is! bool ||
        data['created'] is! bool) {
      throw const FormatException('Invalid buyer profile');
    }
  }

  Future<Map<String,dynamic>> request(
    String method,
    String path, {
    Map<String,String>? query,
    Map<String,dynamic>? body,
    String? bearer,
    String? idempotencyKey,
  }) async {
    final headers = <String,String>{'Accept':'application/json'};
    if (body != null) headers['Content-Type']='application/json';
    if (bearer != null) headers['Authorization']='Bearer '+_requireToken(bearer);
    if (idempotencyKey != null) headers['Idempotency-Key']=_requireKey(idempotencyKey);
    final req = http.Request(method,url(path,query))..headers.addAll(headers);
    if (body != null) req.body=jsonEncode(body);

    Future<http.Response> perform() async {
      return http.Response.fromStream(await client.send(req));
    }

    // Includes connection and body read: a stalled server cannot freeze a page forever.
    final response=await perform().timeout(requestTimeout);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw ZakazApiError(response.statusCode);
    }
    if (response.body.isEmpty) return {};
    final decoded=jsonDecode(utf8.decode(response.bodyBytes));
    if (decoded is! Map<String,dynamic>) {
      throw const FormatException('Invalid JSON object');
    }
    return decoded;
  }

  Future<Map<String,dynamic>> catalog({String query='',int page=0}) async {
    if (page<0 || page>1000 || query.length>80 ||
        !RegExp(r'^[\p{L}\p{N} .-]*$',unicode:true).hasMatch(query)) {
      throw ArgumentError('Invalid catalog search');
    }
    final data=await request('GET','/api/v1/public/catalog',
      query:{'query':query,'page':'$page','size':'12'});
    if (data['page'] != page || data['size'] != 12 ||
        data['total'] is! int || (data['total'] as int)<0) {
      throw const FormatException('Invalid catalog pagination');
    }
    for (final item in _array(data,'items')) {
      _catalogItem(item);
    }
    return data;
  }

  Future<Map<String,dynamic>> qr(String token) async {
    if (!validQrToken(token)) throw ArgumentError('Invalid QR token');
    final data=await request('GET','/api/v1/public/q/'+Uri.encodeComponent(token));
    _nonempty(data,'listingId');
    _nonempty(data,'sellerId');
    _nonempty(data,'title');
    for(final variant in _array(data,'variants')) {
      _variant(variant);
    }
    return data;
  }

  Future<void> lead(String token,String phone,String key) async {
    if (!validQrToken(token) || !validContactPhone(phone)) {
      throw ArgumentError('Invalid guest contact');
    }
    await request('POST','/api/v1/public/q/'+Uri.encodeComponent(token)+'/leads',
      idempotencyKey:_requireKey(key),
      body:{'phone':phone.trim(),'consent':true});
  }

  Future<Map<String,dynamic>?> buyerProfile(String bearer) async {
    try {
      final data=await request('GET','/api/v1/buyers/me',bearer:_requireToken(bearer));
      _profile(data);
      return data;
    } on ZakazApiError catch(e) {
      if(e.statusCode==404) return null;
      rethrow;
    }
  }

  Future<Map<String,dynamic>> enrollBuyer(
    String bearer,String nickname,String phone,bool consent,
  ) async {
    if (!validBuyerNickname(nickname) || !validContactPhone(phone) || !consent) {
      throw ArgumentError('Invalid enrollment');
    }
    final data=await request('POST','/api/v1/buyers/enroll',
      bearer:_requireToken(bearer),
      body:{'nickname':nickname,'phone':phone.trim(),'consentToStorePhone':consent});
    _profile(data);
    return data;
  }

  Future<String> createDemand(
    String bearer,String productId,String unit,String city,String quantity,
  ) async {
    if (productId.isEmpty || city.trim().isEmpty ||
        !validDemandQuantity(quantity,unit)) {
      throw ArgumentError('Invalid buyer demand');
    }
    final now=DateTime.now().toUtc().add(const Duration(days:7));
    final response=await request('POST','/api/v1/buyers/demands',
      bearer:_requireToken(bearer),
      body:{'city':city.trim(),'expiresAt':now.toIso8601String(),
        'lines':[{'productId':productId,'unit':unit,'quantity':quantity.trim()}]});
    return _nonempty(response,'demandId');
  }

  Future<Map<String,dynamic>> bids(String bearer,String demandId) async {
    final data=await request('GET',
      '/api/v1/buyers/demands/'+Uri.encodeComponent(demandId)+'/bids',
      bearer:_requireToken(bearer));
    if(_nonempty(data,'demandId') != demandId) {
      throw const FormatException('Bid response belongs to another demand');
    }
    final accepted=data['acceptedOrderId'];
    if(accepted != null && accepted is! String) {
      throw const FormatException('Invalid accepted order');
    }
    for(final raw in _array(data,'bids')) {
      if(raw is! Map<String,dynamic>) throw const FormatException('Invalid bid');
      for(final field in ['bidId','sellerId','totalKzt','method','deliveryFeeKzt']) {
        _nonempty(raw,field);
      }
      _array(raw,'lines');
    }
    return data;
  }

  Future<String> accept(
    String bearer,String demandId,String bidId,String idempotencyKey,
  ) async {
    final result=await request('POST',
      '/api/v1/buyers/demands/'+Uri.encodeComponent(demandId)+
        '/bids/'+Uri.encodeComponent(bidId)+'/accept',
      bearer:_requireToken(bearer),
      idempotencyKey:_requireKey(idempotencyKey));
    return _nonempty(result,'orderId');
  }

  Future<void> cancelOrder(String bearer,String orderId) async {
    if(orderId.isEmpty) throw ArgumentError('Order ID required');
    await request('POST',
      '/api/v1/buyers/orders/'+Uri.encodeComponent(orderId)+'/cancel',
      bearer:_requireToken(bearer));
  }

  Future<Map<String,dynamic>> order(String bearer,String orderId) async {
    final data=await request('GET',
      '/api/v1/buyers/orders/'+Uri.encodeComponent(orderId),
      bearer:_requireToken(bearer));
    if(_nonempty(data,'orderId') != orderId) {
      throw const FormatException('Order response belongs to another order');
    }
    _nonempty(data,'status');
    _nonempty(data,'currency');
    _array(data,'lines');
    final total=data['totalAmount'];
    final fee=data['deliveryFee'];
    final amountPattern=RegExp(r'^\d{1,17}\.\d{2}$');
    if(total is! String || fee is! String ||
        !amountPattern.hasMatch(total) || !amountPattern.hasMatch(fee)) {
      throw const FormatException('Order money must be an exact decimal string');
    }
    for(final raw in data['lines'] as List<dynamic>) {
      if(raw is! Map<String,dynamic> || raw['quantity'] is! String ||
          raw['lineTotal'] is! String) {
        throw const FormatException('Order line amounts must be decimal strings');
      }
    }
    return data;
  }


  static final _sellerUuid=RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'+String.fromCharCode(36));
  static String _sellerId(String value){
    if(!_sellerUuid.hasMatch(value))throw ArgumentError('Invalid seller resource ID');
    return value;
  }

  Future<List<dynamic>> _sellerList(String bearer,String path) async {
    final response=await client.get(url(path),headers:{
      'Authorization':'Bearer '+_requireToken(bearer),
      'Accept':'application/json'
    }).timeout(requestTimeout);
    if(response.statusCode!=200)throw ZakazApiError(response.statusCode);
    final parsed=jsonDecode(utf8.decode(response.bodyBytes));
    if(parsed is! List<dynamic>)throw const FormatException('Expected seller array');
    return parsed;
  }

  Future<List<dynamic>> sellerAccounts(String bearer) async {
    final items=await _sellerList(bearer,'/api/v1/sellers/me');
    for(final item in items){
      if(item is! Map<String,dynamic>||
         item['sellerId'] is! String ||
         !_sellerUuid.hasMatch(item['sellerId'] as String) ||
         item['role']!='SELLER_ADMIN') {
        throw const FormatException('Invalid seller membership');
      }
    }
    return items;
  }

  Future<Map<String,dynamic>> sellerOrders(String bearer,String sellerId,
      [int page=0]) async {
    _sellerId(sellerId);
    if(page<0||page>1000)throw ArgumentError('Invalid seller order page');
    final data=await request('GET',
      '/api/v1/sellers/'+sellerId+'/orders',bearer:_requireToken(bearer),
      query:{'page':page.toString(),'size':'20','status':'ALL'});
    if(data['page']!=page||data['size']!=20||
       data['total'] is! int || (data['total'] as int)<0) {
      throw const FormatException('Invalid order pagination');
    }
    final amounts=RegExp(r'^\d{1,17}\.\d{2}'+String.fromCharCode(36));
    for(final raw in _array(data,'items')){
      if(raw is! Map<String,dynamic> ||
         raw['orderId'] is! String || !_sellerUuid.hasMatch(raw['orderId'] as String) ||
         raw['status'] is! String || raw['totalAmount'] is! String ||
         !amounts.hasMatch(raw['totalAmount'] as String) ||
         raw['deliveryFee'] is! String ||
         !amounts.hasMatch(raw['deliveryFee'] as String) ||
         raw['currency']!='KZT'||raw['lines'] is! List<dynamic>){
        throw const FormatException('Invalid seller order');
      }
    }
    return data;
  }


  Future<void> _sellerNoContent(String bearer,String method,String path,
      [Map<String,dynamic>? payload]) async {
    final req=http.Request(method,url(path));
    req.headers['Authorization']='Bearer '+_requireToken(bearer);
    req.headers['Accept']='application/json';
    if(payload!=null){
      req.headers['Content-Type']='application/json';
      req.body=jsonEncode(payload);
    }
    final response=await http.Response.fromStream(
      await client.send(req)).timeout(requestTimeout);
    if(response.statusCode!=204)throw ZakazApiError(response.statusCode);
  }

  Future<void> sellerOrderAction(
      String bearer,String sellerId,String orderId,String action) async {
    _sellerId(sellerId);_sellerId(orderId);
    if(action!='confirm'&&action!='cancel')throw ArgumentError('Invalid seller action');
    await _sellerNoContent(bearer,'POST',
      '/api/v1/sellers/'+sellerId+'/orders/'+orderId+'/'+action);
  }

  Future<List<dynamic>> sellerLeads(String bearer,String sellerId) async {
    _sellerId(sellerId);
    final items=await _sellerList(bearer,
      '/api/v1/sellers/'+sellerId+'/leads?page=0&size=20');
    for(final item in items){
      if(item is! Map<String,dynamic> ||
         item['leadId'] is! String || !_sellerUuid.hasMatch(item['leadId'] as String) ||
         item['listingId'] is! String || !_sellerUuid.hasMatch(item['listingId'] as String) ||
         item['phone'] is! String || item['receivedAt'] is! String ||
         !['NEW','CONTACTED','DECLINED'].contains(item['status'])) {
        throw const FormatException('Invalid callback data');
      }
    }
    return items;
  }

  Future<void> sellerLeadStatus(String bearer,String sellerId,
      String leadId,String status) async {
    _sellerId(sellerId);_sellerId(leadId);
    if(!['CONTACTED','DECLINED'].contains(status))throw ArgumentError('Invalid lead status');
    await _sellerNoContent(bearer,'PUT',
      '/api/v1/sellers/'+sellerId+'/leads/'+leadId+'/status',{'status':status});
  }

  Future<List<dynamic>> sellerInvitations(String bearer,String sellerId) async {
    _sellerId(sellerId);
    final items=await _sellerList(bearer,
      '/api/v1/sellers/'+sellerId+'/invitations?limit=24');
    for(final item in items){
      if(item is! Map<String,dynamic>||
         item['invitationId'] is! String || item['demandId'] is! String ||
         !_sellerUuid.hasMatch(item['invitationId'] as String)||
         !_sellerUuid.hasMatch(item['demandId'] as String)||
         item['deliveredAt'] is! String){
        throw const FormatException('Invalid invitation');
      }
    }
    return items;
  }


  /// Seller-only invitation projection. No buyer identifiers or PII.
  Future<Map<String,dynamic>> sellerBidContext(
      String bearer,String sellerId,String invitationId) async {
    _sellerId(sellerId);_sellerId(invitationId);
    final result=await request('GET',
      '/api/v1/sellers/'+sellerId+'/invitations/'+invitationId+'/quote-context',
      bearer:_requireToken(bearer));
    if(result['invitationId']!=invitationId ||
       result['demandId'] is! String ||
       !_sellerUuid.hasMatch(result['demandId'] as String) ||
       result['destinationCity'] is! String ||
       result['expiresAt'] is! String ||
       result['nextRevision'] is! int ||
       (result['nextRevision'] as int)<1 ||
       result['lines'] is! List<dynamic> ||
       (result['lines'] as List).isEmpty ||
       result['origins'] is! List<dynamic>) {
      throw const FormatException('Invalid seller bid context');
    }
    for(final raw in result['lines'] as List<dynamic>) {
      if(raw is! Map<String,dynamic> ||
         raw['productId'] is! String ||
         !_sellerUuid.hasMatch(raw['productId'] as String) ||
         raw['productName'] is! String ||
         raw['quantity'] is! String ||
         raw['unit'] is! String ||
         raw['skus'] is! List<dynamic> ||
         (raw['skus'] as List).isEmpty) {
        throw const FormatException('Invalid seller bid request line');
      }
      for(final candidate in raw['skus'] as List) {
        if(candidate is! Map<String,dynamic> ||
           candidate['skuId'] is! String ||
           !_sellerUuid.hasMatch(candidate['skuId'] as String) ||
           candidate['code'] is! String ||
           candidate['availableByOrigin'] is! Map<String,dynamic>) {
          throw const FormatException('Invalid candidate SKU');
        }
      }
    }
    for(final origin in result['origins'] as List<dynamic>) {
      if(origin is! Map<String,dynamic> ||
         origin['originLocationId'] is! String ||
         !_sellerUuid.hasMatch(origin['originLocationId'] as String) ||
         origin['city'] is! String ||
         origin['pickup'] is! bool || origin['delivery'] is! bool) {
        throw const FormatException('Invalid seller bid origin');
      }
    }
    return result;
  }

  /// A bid is accepted only on a real 201 response with a server-issued UUID.
  Future<String> sellerSubmitInvitationBid(
      String bearer,String sellerId,String invitationId,
      Map<String,dynamic> payload,{String? idempotencyKey}) async {
    _sellerId(sellerId);_sellerId(invitationId);
    if(idempotencyKey!=null &&
       !RegExp(r'^[A-Za-z0-9_.:-]{1,80}'/api/v1/sellers/'+sellerId+
      '/invitations/'+invitationId+'/bid';
    final req=http.Request('POST',url(path));
    req.headers.addAll({
      'Authorization':'Bearer '+_requireToken(bearer),
      'Accept':'application/json',
      'Content-Type':'application/json',
      if(idempotencyKey!=null)'Idempotency-Key':idempotencyKey
    });
    req.body=jsonEncode(payload);
    final response=await http.Response.fromStream(
      await client.send(req)).timeout(requestTimeout);
    if(response.statusCode!=201)throw ZakazApiError(response.statusCode);
    final result=jsonDecode(utf8.decode(response.bodyBytes));
    if(result is! Map<String,dynamic> ||
       result['bidId'] is! String ||
       !_sellerUuid.hasMatch(result['bidId'] as String)) {
      throw const FormatException('Invalid seller bid receipt');
    }
    return result['bidId'] as String;
  }


  Future<bool> buyerDemandSharing(String bearer,String demandId) async {
    _sellerId(demandId);
    final state=await request('GET',
      '/api/v1/buyers/demands/'+demandId+'/invitation-sharing',
      bearer:_requireToken(bearer));
    if(state['enabled'] is! bool)throw const FormatException('Invalid buyer sharing');
    return state['enabled'] as bool;
  }

  Future<void> setBuyerDemandSharing(
      String bearer,String demandId,bool enabled) async {
    _sellerId(demandId);
    await _sellerNoContent(bearer,'PUT',
      '/api/v1/buyers/demands/'+demandId+'/invitation-sharing',
      {'enabled':enabled});
  }

  Future<bool> sellerInvitationPreference(String bearer,String sellerId) async {
    _sellerId(sellerId);
    final state=await request('GET',
      '/api/v1/sellers/'+sellerId+'/invitation-preference',
      bearer:_requireToken(bearer));
    if(state['enabled'] is! bool)throw const FormatException('Invalid seller preference');
    return state['enabled'] as bool;
  }

  Future<void> setSellerInvitationPreference(
      String bearer,String sellerId,bool enabled) async {
    _sellerId(sellerId);
    await _sellerNoContent(bearer,'PUT',
      '/api/v1/sellers/'+sellerId+'/invitation-preference',
      {'enabled':enabled});
  }

  void dispose()=>client.close();
}
