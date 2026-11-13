import 'package:flutter/material.dart';
import 'zakaz_api.dart';
import 'catalog_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ZakazApp());
}

class ZakazApp extends StatelessWidget {
  const ZakazApp({super.key});
  static const apiBase=String.fromEnvironment('ZAKAZ_API_BASE_URL');
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title:'ZAKAZ',
      debugShowCheckedModeBanner:false,
      theme:ThemeData(colorScheme:ColorScheme.fromSeed(seedColor:const Color(0xFF086B7B)),
                     useMaterial3:true),
      home:apiBase.isEmpty
        ? const Scaffold(body:Center(child:Padding(
            padding:EdgeInsets.all(24),
            child:Text('ZAKAZ_API_BASE_URL не задан. Укажите HTTPS Backend через --dart-define.'))))
        : CatalogScreen(api:ZakazApi(apiBase)),
    );
  }
}
