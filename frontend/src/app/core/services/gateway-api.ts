import { Injectable, inject } from '@angular/core';
import { HttpClient , HttpHeaders  } from '@angular/common/http';
import { Observable } from 'rxjs';

import {
  ChatRequest,
  GatewayResponse,
  GatewayStatus
} from '../models/gateway.models';

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

    sendChat(request: ChatRequest,demoAccessCode?: string): Observable<GatewayResponse> {

        let headers = new HttpHeaders();

        if (demoAccessCode) {
          headers = headers.set(
            'X-Demo-Access-Code',
            demoAccessCode
          );
        }

        return this.http.post<GatewayResponse>(
          `${this.baseUrl}/chat`,
          request,
          { headers }
        );
    }

  health(): Observable<{ status: string }> {
    return this.http.get<{ status: string }>('/actuator/health');
  }
}