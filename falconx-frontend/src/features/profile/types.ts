/** STAGE-1B-USER-PROFILE：用户基础资料类型，对齐 identity contract。 */

export type Gender = 1 | 2 | 9;

export type UserProfile = {
  userId: string;
  // 5 强制
  firstName: string;
  middleName: string | null;
  lastName: string;
  birthDate: string;          // ISO yyyy-MM-dd
  nationality: string;        // ISO 3166-1 alpha-3
  // 可选
  gender: Gender | null;
  residenceCountry: string | null;
  residenceState: string | null;
  residenceCity: string | null;
  residenceAddress: string | null;
  residencePostalCode: string | null;
  phoneCountryCode: string | null;
  phoneNumber: string | null;
  // 偏好
  languagePreference: string;
  timezone: string;
  // KYC 状态
  profileVerified: boolean;
};

export type UpdateProfileCommand = Partial<{
  firstName: string;
  middleName: string | null;
  lastName: string;
  birthDate: string;
  nationality: string;
  gender: Gender;
  residenceCountry: string;
  residenceState: string;
  residenceCity: string;
  residenceAddress: string;
  residencePostalCode: string;
  phoneCountryCode: string;
  phoneNumber: string;
  languagePreference: string;
  timezone: string;
}>;
