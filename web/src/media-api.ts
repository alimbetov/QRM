import {publicQrApiBase} from './qr-api';

export type ListingPhoto = {
  mediaId: string;
  cover: boolean;
  width: number;
  height: number;
  thumbnailUrl: string;
  imageUrl: string;
};

export function publicListingGalleryUrl(listingId: string): string {
  if (!/^[0-9a-f-]{36}$/.test(listingId)) throw new Error('Invalid Listing ID');
  return publicQrApiBase()+'/api/v1/public/catalog/'+listingId+'/media';
}

export function trustedListingPhotoUrl(relative: string): string | null {
  if (!/^\/api\/v1\/public\/media\/[0-9a-f-]{36}\?size=(thumb|full)$/.test(relative))
    return null;
  return publicQrApiBase()+relative;
}

export async function loadListingPhotos(listingId: string, signal?: AbortSignal): Promise<ListingPhoto[]> {
  const response=await fetch(publicListingGalleryUrl(listingId),{
    headers:{Accept:'application/json'},credentials:'omit',signal
  });
  if(!response.ok) throw new Error('Listing gallery HTTP '+response.status);
  const data=await response.json() as ListingPhoto[];
  if(!Array.isArray(data) || data.length>20 || data.some(photo=>
      !trustedListingPhotoUrl(photo.imageUrl) ||
      !trustedListingPhotoUrl(photo.thumbnailUrl) ||
      !Number.isInteger(photo.width) || photo.width<1 ||
      !Number.isInteger(photo.height) || photo.height<1))
    throw new Error('Invalid listing gallery');
  return data;
}
