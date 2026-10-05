import {
  MetaApiError,
  RateLimitError,
  TokenExpiredError,
} from "@/lib/meta/client";

export class DeliveryUnconfirmedError extends Error {
  constructor(error: unknown) {
    const detail =
      error instanceof Error ? error.message : "Unknown send outcome";
    super(
      `Message delivery is unconfirmed; automatic retries stopped. Inspect the Instagram inbox before retrying. ${detail}`,
    );
    this.name = "DeliveryUnconfirmedError";
  }
}

export function isDeliveryUnconfirmed(
  error: unknown,
): error is DeliveryUnconfirmedError {
  return error instanceof DeliveryUnconfirmedError;
}

export function isConfirmedSendRejection(error: unknown): boolean {
  return (
    error instanceof RateLimitError ||
    error instanceof TokenExpiredError ||
    (error instanceof MetaApiError && [10, 100, 200, 551].includes(error.code))
  );
}

/**
 * Rejections that cannot be fixed by retrying the same job. These should be
 * archived immediately instead of consuming the worker's retry budget.
 *
 * Meta code 200 is used here only for the explicit Instagram Direct Messaging
 * access-disabled response. Other code-200 responses remain on the normal
 * confirmed-rejection path unless their message proves this exact condition.
 */
export function isPermanentSendRejection(error: unknown): boolean {
  return (
    error instanceof MetaApiError &&
    error.code === 200 &&
    /account owner has disabled access to Instagram Direct Messaging/i.test(
      error.message,
    )
  );
}

export function classifySendError(error: unknown): unknown {
  return isDeliveryUnconfirmed(error) || isConfirmedSendRejection(error)
    ? error
    : new DeliveryUnconfirmedError(error);
}

export function hasLegacyUnconfirmedDelivery(
  error: string | null | undefined,
): boolean {
  return Boolean(
    error &&
    /(?:MetaApiError (?:1|2|5\d\d):|\[code=(?:1|2|5\d\d)\b)/.test(error),
  );
}
