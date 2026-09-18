import { TestBed } from '@angular/core/testing';

import { GatewayApi } from './gateway-api';

describe('GatewayApi', () => {
  let service: GatewayApi;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(GatewayApi);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
