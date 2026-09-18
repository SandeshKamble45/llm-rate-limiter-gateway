export interface TokenBucketStatus {
  availableTokens: number;
  capacity: number;
  refillRatePerSecond: number;
}

export interface SlidingWindowStatus {
  currentRequests: number;
  maxRequests: number;
  windowSeconds: number;
  remainingRequests: number;
}

export interface BudgetStatus {
  allowed: boolean;
  spentMicrodollars: number;
  remainingMicrodollars: number;
}

export interface CircuitBreakerStatus {
  name: string;
  state: string;
  failureRate: number;
  numberOfBufferedCalls: number;
  numberOfFailedCalls: number;
}

export interface GatewayStatus {
  tenantId: string;
  tokenBucket: TokenBucketStatus;
  slidingWindow: SlidingWindowStatus;
  dailyBudget: BudgetStatus;
  circuitBreaker: CircuitBreakerStatus;
}

export interface UsageMetadata {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
}

export interface LlmResponse {
  modelUsed: string;
  completion: string;
  usage: UsageMetadata;
}

export interface BudgetSettlement {
  reservedMicrodollars: number;
  actualMicrodollars: number;
  adjustmentMicrodollars: number;
  spentMicrodollars: number;
  remainingMicrodollars: number;
  withinBudget: boolean;
}

export interface GatewayResponse {
  response: LlmResponse;
  estimatedCostMicrodollars: number;
  actualCostMicrodollars: number;
  settlement: BudgetSettlement;
}

export interface ChatRequest {
  tenantId: string;
  prompt: string;
}
