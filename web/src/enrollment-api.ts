import { publicQrApiBase } from './qr-api';

export type BuyerProfile = {
  buyerId: string;
  nickname: string;
  maskedPhone: string;
  phoneVerified: boolean;
  email: string;
  created: boolean;
};

async function request<T>(token: string, path: string, body?: object): Promise<T | null> {
  if (!token) throw new Error('OIDC access token required');
  const response=await fetch(publicQrApiBase()+path,{
    method:body?'POST':'GET',
    headers:{
      Authorization:'Bearer '+token,
      Accept:'application/json',
      ...(body?{'Content-Type':'application/json'}:{})
    },
    cache:'no-store',credentials:'omit',
    ...(body?{body:JSON.stringify(body)}:{})
  });
  if(response.status===404 && !body) return null;
  if(!response.ok) throw new Error('Buyer enrollment HTTP '+response.status);
  return await response.json() as T;
}

export async function loadBuyerProfile(token:string):Promise<BuyerProfile|null> {
  return request<BuyerProfile>(token,'/api/v1/buyers/me');
}

export async function enrollBuyer(
  token:string,nickname:string,phone:string,consentToStorePhone:boolean
):Promise<BuyerProfile> {
  const profile=await request<BuyerProfile>(token,'/api/v1/buyers/enroll',{
    nickname,phone,consentToStorePhone
  });
  if(!profile) throw new Error('Enrollment did not return a profile');
  return profile;
}
