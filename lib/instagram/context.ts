import { decryptToken } from "@/lib/meta/oauth";

export type InstagramContext = {
  provider: "META";
  accessToken: string;
};

export type ProviderAccount = {
  accessToken: string;
};

export function hasInstagramCredentials(
  account: Pick<ProviderAccount, "accessToken">
) {
  return Boolean(account.accessToken);
}

export async function createInstagramContext(
  account: ProviderAccount,
  _operationId?: string
): Promise<InstagramContext> {
  return {
    provider: "META",
    accessToken: decryptToken(account.accessToken),
  };
}
