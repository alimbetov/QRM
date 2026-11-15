/// Shared, deterministic client validation. Server-side rules remain authoritative.
final RegExp _phonePattern = RegExp(r'^\+[1-9][0-9]{7,14}$');
final RegExp _nicknamePattern =
    RegExp(r'^[\p{L}\p{N}][\p{L}\p{N}_.-]{2,39}$', unicode: true);
final RegExp _quantityPattern = RegExp(r'^\d{1,10}(\.\d{1,3})?$');
final RegExp _qrPattern = RegExp(r'^[A-Za-z0-9_-]{43}$');
final RegExp _pricePattern = RegExp(r'^(0|[1-9]\d{0,16})\.(\d{2})$');

bool validContactPhone(String phone) => _phonePattern.hasMatch(phone.trim());

bool validBuyerNickname(String nickname) => _nicknamePattern.hasMatch(nickname);

bool validQrToken(String token) => _qrPattern.hasMatch(token);

bool validDemandQuantity(String input, String unit) {
  final raw = input.trim();
  if (!_quantityPattern.hasMatch(raw)) return false;
  final amount = num.tryParse(raw);
  if (amount == null || amount <= 0) return false;
  // PCS and LICENSE cannot be fractional, including fractions written with 3 decimals.
  if (unit == 'PCS' || unit == 'LICENSE') {
    final parts = raw.split('.');
    return parts.length == 1 || parts.last.replaceAll('0', '').isEmpty;
  }
  return true;
}

String formatPublishedPriceKzt(Object? amount) {
  if (amount == null || amount is! String) return 'Цена уточняется';
  final match = _pricePattern.firstMatch(amount);
  if (match == null) return 'Цена уточняется';
  final whole = match.group(1)!;
  final grouped = whole.replaceAllMapped(
    RegExp(r'\B(?=(\d{3})+(?!\d))'), (_) => '\u00a0');
  return grouped + ',' + match.group(2)! + ' ₸';
}

String shortPublicId(String? value) {
  if (value == null || value.isEmpty) return '—';
  return value.length <= 8 ? value : value.substring(0, 8);
}
