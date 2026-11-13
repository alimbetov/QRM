import 'dart:convert';
import 'package:http/http.dart' as http;

/// Same API contract as the React buyer client. No device-generated buyer IDs.
class ZakazApi {
  final Uri origin;
  final http.Client client;
  ZakazApi(String baseUrl, {http.Client? client})
      : origin = Uri.parse(baseUrl),
        client = client ?? http.Client() {
    final loopback = {'localhost', '127.0.0.1', '10.0.2.2'};
    if (origin.host.isEmpty ||
        !(origin.scheme == 'https' ||
          (origin.scheme == 'http' && loopback.contains(origin.host)))) {
      throw ArgumentError('HTTPS API URL required outside local emulator');
    }
  }

  Uri url(String path, [Map<String, String>? params]) {
    final uri = origin.resolve(path);
    return params == null ? uri : uri.replace(queryParameters: params);
  }

  Future<Map<String, dynamic>> request(
    String method,
    String path, {
    Map<String, String>? query,
    Map<String, dynamic>? body,
    String? bearer,
    String? idempotencyKey,
  }) async {
    final headers = <String,String>{'Accept': 'application/json'};
    if (body != null) headers['Content-Type'] = 'application/json';
    if (bearer != null && bearer.isNotEmpty) headers['Authorization'] = 'Bearer $bearer';
    if (idempotencyKey != null) headers['Idempotency-Key'] = idempotencyKey;
    final request = http.Request(method, url(path,query))..headers.addAll(headers);
    if (body != null) request.body = jsonEncode(body);
    final response = await http.Response.fromStream(await client.send(request));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw StateError('API HTTP ${response.statusCode}');
    }
    if (response.body.isEmpty) return {};
    final decoded = jsonDecode(utf8.decode(response.bodyBytes));
    if (decoded is! Map<String, dynamic>) throw const FormatException('Invalid JSON object');
    return decoded;
  }

  Future<Map<String,dynamic>> catalog({String query='',int page=0}) => request(
    'GET', '/api/v1/public/catalog',
    query: {'query': query, 'page':'$page', 'size':'12'});

  Future<Map<String,dynamic>> qr(String token) => request(
    'GET','/api/v1/public/q/${Uri.encodeComponent(token)}');

  Future<void> lead(String token, String phone, String key) async {
    await request('POST','/api/v1/public/q/${Uri.encodeComponent(token)}/leads',
      idempotencyKey:key,body:{'phone':phone,'consent':true});
  }

  Future<String> createDemand(
    String bearer, String productId, String unit, String city, String quantity
  ) async {
    final now=DateTime.now().toUtc().add(const Duration(days:7));
    final response=await request('POST','/api/v1/buyers/demands',bearer:bearer,
      body:{'city':city,'expiresAt':now.toIso8601String(),
            'lines':[{'productId':productId,'unit':unit,'quantity':quantity}]});
    return response['demandId'] as String;
  }

  Future<Map<String,dynamic>> bids(String bearer, String demandId) =>
    request('GET','/api/v1/buyers/demands/${Uri.encodeComponent(demandId)}/bids',bearer:bearer);

  Future<String> accept(
    String bearer, String demandId, String bidId, String idempotencyKey
  ) async {
    final result=await request('POST',
      '/api/v1/buyers/demands/${Uri.encodeComponent(demandId)}/bids/${Uri.encodeComponent(bidId)}/accept',
      bearer:bearer,idempotencyKey:idempotencyKey);
    return result['orderId'] as String;
  }

  Future<Map<String,dynamic>> order(String bearer, String orderId) =>
    request('GET','/api/v1/buyers/orders/${Uri.encodeComponent(orderId)}',bearer:bearer);

  void dispose()=>client.close();
}
