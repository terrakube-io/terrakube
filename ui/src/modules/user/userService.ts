import { UserToken } from "@/modules/user/types";
import { CreatedToken, CreateTokenForm } from "@/modules/token/types";
import { apiDelete, apiGet, apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";

async function listPersonalAccessTokens(): Promise<ApiResponse<UserToken[]>> {
  return await apiGet("/pat/v1");
}

async function deletePersonalAccessToken(tokenId: string) {
  return await apiDelete(`/pat/v1/${tokenId}`);
}
async function createPersonalAccessToken(values: CreateTokenForm): Promise<ApiResponse<CreatedToken>> {
  return await apiPost("/pat/v1", values);
}

const methods = {
  listPersonalAccessTokens,
  deletePersonalAccessToken,
  createPersonalAccessToken,
};

export default methods;
