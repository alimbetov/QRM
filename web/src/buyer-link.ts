/** Match the same supported demand units as Flutter's native BuyerScreen handoff. */
const supportedUnits = new Set(['PCS','KG','G','L','ML','M','M2','M3','HOUR']);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function buyerDemandPath(
  productId: string,
  variants: readonly {unit: string | null}[]
): string | null {
  if (!uuid.test(productId)) return null;
  const unit=variants.find(v=>v.unit !== null && supportedUnits.has(v.unit))?.unit;
  if (!unit) return null;
  return '/buy?productId='+encodeURIComponent(productId)+'&unit='+encodeURIComponent(unit);
}
