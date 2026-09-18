import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { ChatRequest, GatewayResponse, GatewayStatus } from '../models/gateway.models';

@Injectable({
  providedIn: 'root',
})
export class GatewayApi {
  private readonly http = inject(HttpClient);

  private readonly baseUrl = '/api/v1/gateway';

  getStatus(tenantId: string): Observable<GatewayStatus> {
    return this.http.get<GatewayStatus>(`${this.baseUrl}/status`, {
      params: {
        tenantId,
      },
    });
  }

  sendChat(request: ChatRequest): Observable<GatewayResponse> {
    return this.http.post<GatewayResponse>(`${this.baseUrl}/chat`, request);
  }
}
