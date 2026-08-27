import { request } from '../request';
import { encryptRequestBody } from '@/utils/request-crypto';

export interface ProfileUser {
  userId: number | string;
  deptId?: number | string | null;
  userName: string;
  nickName?: string | null;
  userType?: string | null;
  email?: string | null;
  phoneNumber?: string | null;
  gender?: '0' | '1' | '2' | string | null;
  avatar?: number | null;
  avatarUrl?: string | null;
  loginIp?: string | null;
  loginDate?: string | null;
  deptName?: string | null;
}

export interface UserProfile {
  user: ProfileUser;
  roleGroup?: string | null;
  postGroup?: string | null;
}

export interface UpdateUserProfilePayload {
  nickName?: string;
  email?: string;
  phoneNumber?: string;
  gender?: string;
  avatar?: number | null;
}

export interface EffectiveUiPermissions {
  buttons: string[];
  routes: string[];
}

export function fetchUserProfile() {
  return request<UserProfile>({ url: '/system/user/profile', method: 'get' });
}

export function updateUserProfile(data: UpdateUserProfilePayload) {
  return request<void>({ url: '/system/user/profile', method: 'put', data });
}

export function updateUserPassword(oldPassword: string, newPassword: string) {
  const { encryptedBody, encryptedKey } = encryptRequestBody({ oldPassword, newPassword });
  return request<void>({
    url: '/system/user/profile/updatePwd',
    method: 'put',
    data: encryptedBody,
    headers: { 'encrypt-key': encryptedKey }
  });
}

export function fetchEffectiveUiPermissions() {
  return request<EffectiveUiPermissions>({ url: '/api/portal/auth/permissions', method: 'get' });
}
