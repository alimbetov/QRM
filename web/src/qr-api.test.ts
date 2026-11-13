import {describe,it,expect} from 'vitest';
import { tokenFromPath,publicQrPath,validContactPhone } from './qr-api';
const token='A'.repeat(43);
describe('QR route and guest validation',()=>{
  it('accepts only full-length opaque token without unexpected path components',()=>{
    expect(tokenFromPath('/q/'+token)).toBe(token);
    expect(tokenFromPath('/q/'+token+'/')).toBe(token);
    expect(tokenFromPath('/q/'+token+'/leads')).toBeNull();
    expect(tokenFromPath('/q/123')).toBeNull();
    expect(tokenFromPath('/q/../admin')).toBeNull();
  });
  it('encodes only validated token into public API route',()=>{
    expect(publicQrPath(token)).toBe('/api/v1/public/q/'+token);
    expect(()=>publicQrPath('../../admin')).toThrow();
  });
  it('validates guest phone as E.164, never as account identity',()=>{
    expect(validContactPhone('+77011234567')).toBe(true);
    expect(validContactPhone('+12025550123')).toBe(true);
    expect(validContactPhone('555')).toBe(false);
    expect(validContactPhone('77011234567')).toBe(false);
  });
});
