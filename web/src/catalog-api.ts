import { publicQrApiBase } from './qr-api';

export type LiveVariant = {
  id: string;
  code: string;
  unit: string | null;
  priceKzt: string | null;
};
export type LiveCatalogItem = {
  listingId: string;
  productId: string;
  sellerId: string;
  qrToken: string;
  title: string;
  category: string;
  categoryCode: string;
  productType: 'PHYSICAL' | 'SERVICE' | 'DIGITAL';
  variants: LiveVariant[];
};
export type LiveCategory = { code: string; label: string; productType: string };
export type CatalogPage = {
  page: number;
  size: number;
  total: number;
  items: LiveCatalogItem[];
};

export function catalogUrl(
  query: string,
  categoryCode: string,
  page: number,
  size = 12
): string {
  if (!Number.isInteger(page) || page < 0 || page > 1000) throw new Error('Invalid page');
  if (!Number.isInteger(size) || size < 1 || size > 24) throw new Error('Invalid size');
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (query.trim()) params.set('query', query.trim());
  if (categoryCode) params.set('categoryCode', categoryCode);
  return publicQrApiBase() + '/api/v1/public/catalog?' + params.toString();
}

async function readJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(url, {
    headers: { Accept: 'application/json' },
    credentials: 'omit',
    signal
  });
  if (!response.ok) throw new Error('Catalog HTTP ' + response.status);
  return await response.json() as T;
}

export async function loadCatalog(
  query: string,
  categoryCode: string,
  page: number,
  signal?: AbortSignal
): Promise<CatalogPage> {
  const result = await readJson<CatalogPage>(catalogUrl(query, categoryCode, page), signal);
  if (!result || !Array.isArray(result.items) || !Number.isFinite(result.total) ||
      result.total < 0 || !Number.isInteger(result.page)) {
    throw new Error('Invalid catalog response');
  }
  return result;
}

export async function loadCategories(signal?: AbortSignal): Promise<LiveCategory[]> {
  const result = await readJson<LiveCategory[]>(
    publicQrApiBase() + '/api/v1/public/catalog/categories', signal
  );
  if (!Array.isArray(result)) throw new Error('Invalid category response');
  return result;
}
