import 'package:flutter_test/flutter_test.dart';
import 'package:zakaz_mobile/mobile_validation.dart';

void main() {
  test('PCS and LICENSE do not accept a fractional buyer demand', () {
    for(final unit in ['PCS','LICENSE']) {
      expect(validDemandQuantity('1',unit),isTrue);
      expect(validDemandQuantity('1.000',unit),isTrue);
      expect(validDemandQuantity('1.500',unit),isFalse);
      expect(validDemandQuantity('0.001',unit),isFalse);
    }
    expect(validDemandQuantity('1.500','KG'),isTrue);
    expect(validDemandQuantity('0','KG'),isFalse);
    expect(validDemandQuantity('1.0001','KG'),isFalse);
    expect(validDemandQuantity('1e7','KG'),isFalse);
    expect(validDemandQuantity('-1','KG'),isFalse);
  });

  test('published KZT prices are lossless or explicitly unknown', () {
    expect(formatPublishedPriceKzt('125000.50'),'125\u00a0000,50 ₸');
    expect(formatPublishedPriceKzt('0.00'),'0,00 ₸');
    expect(formatPublishedPriceKzt('1.999'),'Цена уточняется');
    expect(formatPublishedPriceKzt(12.50),'Цена уточняется');
    expect(formatPublishedPriceKzt(null),'Цена уточняется');
  });

  test('public IDs never throw on shortened or missing fields', () {
    expect(shortPublicId('abc'),'abc');
    expect(shortPublicId('abcdefghijkl'),'abcdefgh');
    expect(shortPublicId(null),'—');
    expect(shortPublicId(''),'—');
  });

  test('QR, phone and nickname validation reject malformed identifiers', () {
    expect(validQrToken(List.filled(43,'A').join()),isTrue);
    expect(validQrToken('A'*42),isFalse);
    expect(validQrToken('../../admin'),isFalse);
    expect(validContactPhone('+77001234567'),isTrue);
    expect(validContactPhone('+7700 1234567'),isFalse);
    expect(validBuyerNickname('Buyer_123'),isTrue);
    expect(validBuyerNickname('ab'),isFalse);
  });
}
