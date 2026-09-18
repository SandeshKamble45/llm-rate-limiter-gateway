import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

import { GatewayApi } from './core/services/gateway-api';
import {
  GatewayResponse,
  GatewayStatus
} from './core/models/gateway.models';

@Component({
  selector: 'app-root',
  imports: [CommonModule],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App {

  private readonly gatewayApi = inject(GatewayApi);

  readonly status = signal<GatewayStatus | null>(null);
  readonly error = signal<string | null>(null);

  readonly prompt = signal('');
  readonly isSending = signal(false);
  readonly lastResponse = signal<GatewayResponse | null>(null);

  constructor() {
    this.loadStatus();
  }

  loadStatus(): void {
    this.gatewayApi.getStatus('demo-tenant').subscribe({
      next: (status) => {
        this.status.set(status);
        this.error.set(null);
      },
      error: (error) => {
        console.error('Failed to load gateway status:', error);
        this.error.set('Failed to connect to gateway');
      }
    });
  }

  sendRequest(): void {
    const currentPrompt = this.prompt().trim();

    if (!currentPrompt) {
      this.error.set('Please enter a prompt.');
      return;
    }

    this.isSending.set(true);
    this.error.set(null);

    this.gatewayApi.sendChat({
      tenantId: 'demo-tenant',
      prompt: currentPrompt
    }).subscribe({
      next: (response) => {
        console.log('CHAT RESPONSE:', response);

        this.lastResponse.set(response);
        this.isSending.set(false);

        // Refresh live gateway state after the request.
        this.loadStatus();
      },
      error: (error) => {
        console.error('CHAT ERROR:', error);

        this.isSending.set(false);

        if (error.status === 429) {
          this.error.set(
            `Request rejected by rate limiter${error.error?.retryAfterSeconds
              ? ` — retry after ${error.error.retryAfterSeconds}s`
              : ''}.`
          );
        } else if (error.status === 502) {
          this.error.set(
            'LLM provider request failed. The gateway rejected the operation.'
          );
        } else {
          this.error.set(
            error.error?.message ?? 'Request failed. Please try again.'
          );
        }

        // Even failed requests may change gateway state.
        this.loadStatus();
      }
    });
  }

  onPromptChange(value: string): void {
    this.prompt.set(value);
  }
}