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


export type PhotoFeedItem = Omit<LiveCatalogItem,'sellerId'> & {
  coverUrl: string | null;
  imageCount: number;
};
export type PhotoFeedPage = {
  items: PhotoFeedItem[];
  nextCursor: string | null;
  hasMore: boolean;
};

export function photoFeedUrl(query: string, categoryCode: string,
                             cursor: string | null, limit = 24): string {
  if (!Number.isInteger(limit) || limit < 1 || limit > 48) throw new Error('Invalid feed limit');
  if (query.length > 80) throw new Error('Invalid feed query');
  if (categoryCode && !/^[0-9]{20}$/.test(categoryCode)) throw new Error('Invalid feed category');
  const params = new URLSearchParams({ limit: String(limit) });
  if (query.trim()) params.set('query', query.trim());
  if (categoryCode) params.set('categoryCode', categoryCode);
  if (cursor) params.set('cursor', cursor);
  return publicQrApiBase() + '/api/v1/public/catalog/feed?' + params.toString();
}

export function photoMediaUrl(relative: string | null): string | null {
  if (!relative || !/^\/api\/v1\/public\/media\/[0-9a-f-]{36}\?size=thumb$/.test(relative))
    return null;
  return publicQrApiBase() + relative;
}

export async function loadPhotoFeed(query: string, categoryCode: string,
                                    cursor: string | null,
                                    signal?: AbortSignal): Promise<PhotoFeedPage> {
  const result = await readJson<PhotoFeedPage>(
    photoFeedUrl(query, categoryCode, cursor), signal);
  if (!result || !Array.isArray(result.items) || typeof result.hasMore !== 'boolean' ||
      !(result.nextCursor === null || typeof result.nextCursor === 'string') ||
      result.items.some(item => !item.listingId || !item.productId ||
        !item.qrToken || !Array.isArray(item.variants))) {
    throw new Error('Invalid photo feed response');
  }
  if (result.hasMore && !result.nextCursor) throw new Error('Feed cursor required');
  return result;
}
