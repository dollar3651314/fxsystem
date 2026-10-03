import { requestJson } from "../../lib/api";
import type { UpdateProfileCommand, UserProfile } from "./types";

export function getProfile(accessToken: string) {
  return requestJson<UserProfile>("/api/v1/me/profile", {
    method: "GET",
    token: accessToken,
  });
}

export function updateProfile(accessToken: string, command: UpdateProfileCommand) {
  return requestJson<UserProfile>("/api/v1/me/profile", {
    method: "PUT",
    token: accessToken,
    body: JSON.stringify(command),
  });
}
