import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'buyer_screen.dart';
import 'catalog_screen.dart';
import 'hybrid_route_policy.dart';
import 'zakaz_api.dart';

/// React renders the public catalog and QR pages. All OIDC/commerce stays native.
/// No JavaScript channels or bearer tokens are exposed to the embedded browser.
class HybridCatalogScreen extends StatefulWidget {
  const HybridCatalogScreen({
    required this.api,
    required this.webBaseUrl,
    super.key,
  });

  final ZakazApi api;
  final String webBaseUrl;

  @override
  State<HybridCatalogScreen> createState() => _HybridCatalogScreenState();
}

class _HybridCatalogScreenState extends State<HybridCatalogScreen> {
  late final HybridRoutePolicy _policy;
  late final WebViewController _controller;
  bool _openingBuyer = false;
  bool _pageFailed = false;
  bool _blockedNavigation = false;
  int _progress = 0;

  @override
  void initState() {
    super.initState();
    _policy = HybridRoutePolicy(widget.webBaseUrl);
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setNavigationDelegate(NavigationDelegate(
        onNavigationRequest: _handleNavigation,
        onPageStarted: (_) {
          if (mounted) {
            setState(() {
              _pageFailed = false;
              _blockedNavigation = false;
            });
          }
        },
        onProgress: (value) {
          if (mounted) setState(() => _progress = value);
        },
        onWebResourceError: (error) {
          if (error.isForMainFrame == true && mounted) {
            setState(() => _pageFailed = true);
          }
        },
      ))
      ..loadRequest(_policy.root);
  }

  NavigationDecision _handleNavigation(NavigationRequest request) {
    final route = _policy.classify(request.url);
    if (route.type == HybridRouteType.publicPage) {
      return NavigationDecision.navigate;
    }
    if (route.type == HybridRouteType.nativeBuyer &&
        request.isMainFrame && !_openingBuyer) {
      _openingBuyer = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        Navigator.of(context).push(MaterialPageRoute<void>(
          builder: (_) => BuyerScreen(
            api: widget.api,
            productId: route.productId!,
            unit: route.unit!,
          ),
        )).whenComplete(() => _openingBuyer = false);
      });
      return NavigationDecision.prevent;
    }
    if (request.isMainFrame && mounted && !_openingBuyer) {
      setState(() => _blockedNavigation = true);
    }
    return NavigationDecision.prevent;
  }

  Future<void> _back() async {
    if (await _controller.canGoBack()) {
      await _controller.goBack();
    }
  }

  void _nativeFallback() {
    // The previous shell owns its HTTP client; give the native fallback its
    // own client so dispose() cannot close one still used by the new screen.
    final api = ZakazApi(widget.api.origin.toString());
    Navigator.of(context).pushReplacement(MaterialPageRoute<void>(
      builder: (_) => CatalogScreen(api: api),
    ));
  }

  @override
  void dispose() {
    widget.api.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('ZAKAZ · каталог'),
        actions: [
          IconButton(
            tooltip: 'Назад в каталоге',
            onPressed: _back,
            icon: const Icon(Icons.arrow_back),
          ),
          IconButton(
            tooltip: 'Обновить страницу',
            onPressed: () => _controller.reload(),
            icon: const Icon(Icons.refresh),
          ),
          IconButton(
            tooltip: 'Нативный каталог',
            onPressed: _nativeFallback,
            icon: const Icon(Icons.view_list),
          ),
        ],
      ),
      body: Column(children: [
        if (_progress < 100)
          LinearProgressIndicator(value: _progress / 100),
        if (_blockedNavigation)
          MaterialBanner(
            content: const Text(
              'Внешняя ссылка или недоступный маршрут заблокированы.',
            ),
            actions: [
              TextButton(
                onPressed: () => setState(() => _blockedNavigation = false),
                child: const Text('Закрыть'),
              ),
            ],
          ),
        Expanded(
          child: _pageFailed
              ? Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Text('Не удалось открыть веб-каталог.'),
                      const SizedBox(height: 12),
                      OutlinedButton(
                        onPressed: () => _controller.reload(),
                        child: const Text('Повторить'),
                      ),
                      TextButton(
                        onPressed: _nativeFallback,
                        child: const Text('Открыть нативный каталог'),
                      ),
                    ],
                  ),
                )
              : WebViewWidget(controller: _controller),
        ),
      ]),
    );
  }
}
