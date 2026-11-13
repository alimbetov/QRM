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
    _nonempty(data,'demandId');
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

  Future<Map<String,dynamic>> order(String bearer,String orderId) async {
    final data=await request('GET',
      '/api/v1/buyers/orders/'+Uri.encodeComponent(orderId),
      bearer:_requireToken(bearer));
    _nonempty(data,'orderId');
    _nonempty(data,'status');
    _nonempty(data,'currency');
    _array(data,'lines');
    if(data['totalAmount'] is! num && data['totalAmount'] is! String) {
      throw const FormatException('Invalid order total');
    }
    return data;
  }

  void dispose()=>client.close();
}
