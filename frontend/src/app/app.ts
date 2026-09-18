import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

import { GatewayApi } from './core/services/gateway-api';
import {
  GatewayResponse,
  GatewayStatus
} from './core/models/gateway.models';

interface RequestError {
  status: number;
  title: string;
  message: string;
  retryAfterSeconds?: number;
}

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
  readonly requestError = signal<RequestError | null>(null);

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
      this.requestError.set({
        status: 400,
        title: 'VALIDATION ERROR',
        message: 'Please enter a prompt before sending the request.'
      });

      return;
    }

    this.isSending.set(true);
    this.requestError.set(null);

    this.gatewayApi.sendChat({
      tenantId: 'demo-tenant',
      prompt: currentPrompt
    }).subscribe({
      next: (response) => {
        console.log('CHAT RESPONSE:', response);

        this.lastResponse.set(response);
        this.requestError.set(null);
        this.isSending.set(false);

        /*
         * Refresh Redis-backed gateway state after the request.
         */
        this.loadStatus();
      },

      error: (error) => {
        console.error('CHAT ERROR:', error);

        this.isSending.set(false);

        this.requestError.set(
          this.mapRequestError(error)
        );

        /*
         * Failed requests can still affect gateway state.
         * For example, a rate-limit rejection updates the
         * sliding-window state.
         */
        this.loadStatus();
      }
    });
  }

  private mapRequestError(error: {
    status?: number;
    error?: {
      message?: string;
      retryAfterSeconds?: number;
    } | string;
  }): RequestError {

    const status = error.status ?? 0;

    if (status === 429) {
      const retryAfterSeconds =
        typeof error.error === 'object'
          ? error.error?.retryAfterSeconds
          : undefined;

      return {
        status: 429,
        title: 'RATE LIMITED',
        message: 'The gateway rejected the request because the rate limit was reached.',
        retryAfterSeconds
      };
    }

    if (status === 402) {
      return {
        status: 402,
        title: 'BUDGET EXCEEDED',
        message: 'The tenant does not have enough daily budget available for this request.'
      };
    }

    if (status === 502) {
      return {
        status: 502,
        title: 'PROVIDER UNAVAILABLE',
        message: 'The LLM provider operation failed. The gateway released the reserved budget.'
      };
    }

    if (status === 400) {
      const message =
        typeof error.error === 'string'
          ? error.error
          : error.error?.message ?? 'The request is invalid.';

      return {
        status: 400,
        title: 'VALIDATION ERROR',
        message
      };
    }

    return {
      status,
      title: 'REQUEST FAILED',
      message: 'The gateway could not complete the request.'
    };
  }

  onPromptChange(value: string): void {
    this.prompt.set(value);
  }
}