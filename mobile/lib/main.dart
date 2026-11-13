import 'package:flutter/material.dart';
import 'zakaz_api.dart';
import 'catalog_screen.dart';
import 'hybrid_route_policy.dart';
import 'hybrid_catalog_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ZakazApp());
}

class ZakazApp extends StatelessWidget {
  const ZakazApp({super.key});
  static const apiBase=String.fromEnvironment('ZAKAZ_API_BASE_URL');
  static const webBase=String.fromEnvironment('ZAKAZ_WEB_BASE_URL');
  static const hybridEnabled=bool.fromEnvironment(
    'ZAKAZ_HYBRID_WEBVIEW_ENABLED',defaultValue:false);
  static bool get webOriginValid {
    if (!hybridEnabled) return false;
    try {
      HybridRoutePolicy(webBase);
      return true;
    } catch (_) {
      return false;
    }
  }
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
        : hybridEnabled && !webOriginValid
        ? const Scaffold(body:Center(child:Padding(
            padding:EdgeInsets.all(24),
            child:Text('WebView включён, но ZAKAZ_WEB_BASE_URL не является доверенным HTTPS адресом React.'))))
        : hybridEnabled
        ? HybridCatalogScreen(api:ZakazApi(apiBase),webBaseUrl:webBase)
        : CatalogScreen(api:ZakazApi(apiBase)),
    );
  }
}
