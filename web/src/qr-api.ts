export type QrListing = {
  listingId: string;
  sellerId: string;
  title: string;
  category: string;
  variants: Array<{ id: string; code: string; unit: string | null }>;
};

export const qrTokenPattern = /^[A-Za-z0-9_-]{43}$/;

export function tokenFromPath(pathname: string): string | null {
  const match = /^\/q\/([^/]+)\/?$/.exec(pathname);
  if (!match || !qrTokenPattern.test(match[1])) return null;
  return match[1];
}

export function validContactPhone(phone: string): boolean {
  return /^\+[1-9][0-9]{7,14}$/.test(phone.trim());
}

export function publicQrPath(token: string): string {
  if (!qrTokenPattern.test(token)) throw new Error('Invalid QR token');
  return `/api/v1/public/q/${encodeURIComponent(token)}`;
}

export function publicQrApiBase(): string {
  return (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '');
}
