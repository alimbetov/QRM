import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:zakaz_mobile/zakaz_api.dart';

const seller='11111111-1111-4111-8111-111111111111';
const demand='22222222-2222-4222-8222-222222222222';

void main(){
  test('buyer sharing and seller preference are false until explicit server consent',() async {
    var writes=0;
    final client=MockClient((request) async {
      expect(request.headers['authorization']??request.headers['Authorization'],
        'Bearer session-token');
      if(request.method=='GET') {
        return http.Response(jsonEncode({'enabled':false}),200);
      }
      writes++;
      expect(request.method,'PUT');
      expect(jsonDecode(request.body),{'enabled':true});
      return http.Response('',204);
    });
    final api=ZakazApi('https://stage.example.test',client:client);
    expect(await api.buyerDemandSharing('session-token',demand),false);
    expect(await api.sellerInvitationPreference('session-token',seller),false);
    expect(writes,0);
    await api.setBuyerDemandSharing('session-token',demand,true);
    await api.setSellerInvitationPreference('session-token',seller,true);
    expect(writes,2);
    api.dispose();
  });

  test('403 and unexpected consent payload never produce optimistic success',() async {
    final client=MockClient((request) async {
      if(request.method=='GET')return http.Response('{"enabled":"true"}',200);
      return http.Response('denied',403);
    });
    final api=ZakazApi('https://stage.example.test',client:client);
    await expectLater(api.buyerDemandSharing('token',demand),throwsFormatException);
    await expectLater(api.setBuyerDemandSharing('token',demand,true),
      throwsA(isA<ZakazApiError>()));
    api.dispose();
  });
}
