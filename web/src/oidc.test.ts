import { describe, expect, it } from 'vitest';
import { safeReturnPath } from './oidc';

describe('Keycloak callback return-path validation',()=>{
  it('keeps only same-origin absolute paths',()=>{
    expect(safeReturnPath('/buy?productId=123')).toBe('/buy?productId=123');
    expect(safeReturnPath('//evil.example')).toBe('/');
    expect(safeReturnPath('https://evil.example')).toBe('/');
    expect(safeReturnPath('/\\evil.example')).toBe('/');
    expect(safeReturnPath('/buy\nHeader: x')).toBe('/');
    expect(safeReturnPath(null)).toBe('/');
  });
});
