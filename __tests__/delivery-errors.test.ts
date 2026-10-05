import { describe, expect, it } from "vitest";
import { MetaApiError, RateLimitError } from "@/lib/meta/client";
import {
  isConfirmedSendRejection,
  isPermanentSendRejection,
} from "@/lib/instagram/delivery-errors";

describe("Instagram delivery error classification", () => {
  it("treats disabled Instagram Direct Messaging access as permanent", () => {
    const error = new MetaApiError(
      200,
      undefined,
      undefined,
      "The account owner has disabled access to Instagram Direct Messaging. (/v25.0/17841477313480969/messages)",
    );

    expect(isConfirmedSendRejection(error)).toBe(true);
    expect(isPermanentSendRejection(error)).toBe(true);
  });

  it("does not make unrelated code-200 errors permanent", () => {
    const error = new MetaApiError(
      200,
      undefined,
      undefined,
      "Some other Meta API rejection",
    );

    expect(isConfirmedSendRejection(error)).toBe(true);
    expect(isPermanentSendRejection(error)).toBe(false);
  });

  it("keeps rate limits out of the permanent bucket", () => {
    expect(isPermanentSendRejection(new RateLimitError("Rate limited"))).toBe(false);
  });
});
