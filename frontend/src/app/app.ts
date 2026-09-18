import { Component, inject, signal } from '@angular/core';

import { GatewayApi } from './core/services/gateway-api';
import { GatewayStatus } from './core/models/gateway.models';

@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App {

  private readonly gatewayApi = inject(GatewayApi);

  readonly status = signal<GatewayStatus | null>(null);
  readonly error = signal<string | null>(null);

  constructor() {
    console.log('App constructor');

    this.loadStatus();
  }

  loadStatus(): void {
    console.log('Loading gateway status...');

    this.gatewayApi.getStatus('demo-tenant').subscribe({
      next: (status) => {
        console.log('STATUS RECEIVED:', status);

        this.status.set(status);
        this.error.set(null);

        console.log('STATUS ASSIGNED:', status);
      },
      error: (error) => {
        console.error('STATUS ERROR:', error);

        this.error.set('Failed to connect to gateway');
      }
    });
  }
}