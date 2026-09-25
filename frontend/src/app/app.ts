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
  algorithm?: string;
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

  readonly tenantId = signal('demo-tenant');
  readonly prompt = signal('');
  readonly isSending = signal(false);

  readonly lastResponse = signal<GatewayResponse | null>(null);
  readonly requestError = signal<RequestError | null>(null);
  readonly retryCountdown = signal<number | null>(null);

  private retryTimer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    this.loadStatus();
  }

  loadStatus(): void {
    const tenant = this.tenantId().trim();

    if (!tenant) {
      this.status.set(null);
      this.error.set('Enter a tenant ID to load gateway status.');
      return;
    }

    this.gatewayApi.getStatus(tenant).subscribe({
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

  refreshStatus(): void {
    this.stopRetryCountdown();
    this.retryCountdown.set(null);
    this.requestError.set(null);
    this.loadStatus();
  }

  sendRequest(): void {
    const tenant = this.tenantId().trim();
    const currentPrompt = this.prompt().trim();

    if (!tenant) {
      this.requestError.set({
        status: 400,
        title: 'VALIDATION ERROR',
        message: 'Please enter a tenant ID before sending the request.'
      });

      return;
    }

    if (!currentPrompt) {
      this.requestError.set({
        status: 400,
        title: 'VALIDATION ERROR',
        message: 'Please enter a prompt before sending the request.'
      });

      return;
    }

    this.stopRetryCountdown();
    this.retryCountdown.set(null);

    this.isSending.set(true);
    this.requestError.set(null);

    this.gatewayApi.sendChat({
      tenantId: tenant,
      prompt: currentPrompt
    }).subscribe({
      next: (response) => {
        console.log('CHAT RESPONSE:', response);

        this.lastResponse.set(response);
        this.requestError.set(null);
        this.isSending.set(false);

        this.stopRetryCountdown();
        this.retryCountdown.set(null);

        /*
         * Refresh Redis-backed gateway state after the request.
         */
        this.loadStatus();
      },

      error: (error) => {
        console.error('CHAT ERROR:', error);

        this.isSending.set(false);

        const requestError = this.mapRequestError(error);

        this.requestError.set(requestError);

        if (
          requestError.status === 429 &&
          requestError.retryAfterSeconds !== undefined &&
          requestError.retryAfterSeconds > 0
        ) {
          this.startRetryCountdown(requestError.retryAfterSeconds);
        }

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
      algorithm?: string;
    } | string;
  }): RequestError {

    const status = error.status ?? 0;

    if (status === 429) {
      const responseBody =
        typeof error.error === 'object'
          ? error.error
          : undefined;

      const retryAfterSeconds =
        responseBody?.retryAfterSeconds;

      const algorithm =
        responseBody?.algorithm;

      if (algorithm === 'token-bucket') {
        return {
          status: 429,
          title: 'TOKEN CAPACITY EXHAUSTED',
          message:
            'The gateway rejected the request because the tenant token bucket does not have enough capacity.',
          retryAfterSeconds,
          algorithm
        };
      }

      return {
        status: 429,
        title: 'RATE LIMITED',
        message:
          'The gateway rejected the request because the request rate limit was reached.',
        retryAfterSeconds,
        algorithm
      };
    }

    if (status === 402) {
      return {
        status: 402,
        title: 'BUDGET EXCEEDED',
        message:
          'The tenant does not have enough daily budget available for this request.'
      };
    }

    if (status === 502) {
      return {
        status: 502,
        title: 'PROVIDER UNAVAILABLE',
        message:
          'The LLM provider operation failed. The gateway released the reserved budget.'
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

  onTenantIdChange(value: string): void {
    this.tenantId.set(value);
  }

  onPromptChange(value: string): void {
    this.prompt.set(value);
  }

  private startRetryCountdown(seconds: number): void {
  this.stopRetryCountdown();

  this.retryCountdown.set(seconds);

  this.retryTimer = setInterval(() => {
    const remaining = this.retryCountdown();

    if (remaining === null) {
      this.stopRetryCountdown();
      return;
    }

    if (remaining <= 1) {
      this.stopRetryCountdown();
      this.retryCountdown.set(0);

      // The rate-limit window has expired.
      // Refresh Redis-backed gateway state automatically.
      this.refreshStatus();

      return;
    }

    this.retryCountdown.set(remaining - 1);
  }, 1000);
}

  private stopRetryCountdown(): void {
    if (this.retryTimer !== null) {
      clearInterval(this.retryTimer);
      this.retryTimer = null;
    }
  }
}