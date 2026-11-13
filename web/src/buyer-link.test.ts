import {describe,it,expect} from 'vitest';
import {buyerDemandPath} from './buyer-link';

const productId='1046ca0e-4004-4004-8004-a031ffede023';

describe('native Flutter buyer handoff link',()=>{
  it('chooses first supported PHYSICAL request unit, never first service SKU',()=>{
    expect(buyerDemandPath(productId,[
      {unit:'SERVICE'},{unit:'LICENSE'},{unit:'PCS'},{unit:'KG'}
    ])).toBe('/buy?productId='+productId+'&unit=PCS');
  });
  it('omits link when only unsupported, missing or invalid product variants exist',()=>{
    expect(buyerDemandPath(productId,[{unit:'SERVICE'},{unit:'LICENSE'}])).toBeNull();
    expect(buyerDemandPath(productId,[{unit:null}])).toBeNull();
    expect(buyerDemandPath('invalid-id',[{unit:'PCS'}])).toBeNull();
  });
  it('selects any supported quantity unit without generating arbitrary routes',()=>{
    expect(buyerDemandPath(productId,[{unit:'M2'}])).toContain('&unit=M2');
  });
});
