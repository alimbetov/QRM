import {describe,it,expect,afterEach,vi} from 'vitest';
import {loadListingPhotos,publicListingGalleryUrl,trustedListingPhotoUrl} from './media-api';

afterEach(()=>vi.unstubAllGlobals());

describe('QR seller listing gallery',()=>{
  const id='a'.repeat(36);
  it('uses a trusted same-service URL and no object keys',()=>{
    expect(publicListingGalleryUrl(id)).toContain('/api/v1/public/catalog/'+id+'/media');
    expect(()=>publicListingGalleryUrl('../admin')).toThrow();
    expect(trustedListingPhotoUrl('/api/v1/public/media/'+id+'?size=full')).not.toBeNull();
    expect(trustedListingPhotoUrl('https://attacker.invalid/media.jpg')).toBeNull();
  });
  it('loads safely decoded image metadata only',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({
      ok:true,json:async()=>[{
        mediaId:id,cover:true,width:600,height:400,
        thumbnailUrl:'/api/v1/public/media/'+id+'?size=thumb',
        imageUrl:'/api/v1/public/media/'+id+'?size=full'
      }]
    }));
    const photos=await loadListingPhotos(id);
    expect(photos).toHaveLength(1);
    expect(photos[0].cover).toBe(true);
  });
  it('rejects server-supplied external image URL',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({
      ok:true,json:async()=>[{
        mediaId:id,cover:true,width:600,height:400,
        thumbnailUrl:'/api/v1/public/media/'+id+'?size=thumb',
        imageUrl:'javascript:alert(1)'
      }]
    }));
    await expect(loadListingPhotos(id)).rejects.toThrow('Invalid listing gallery');
  });
});
