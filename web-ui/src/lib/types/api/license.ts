export type LicenseStatusCode =
  | "UNVERIFIED"
  | "LICENSED"
  | "MISSING_FILE"
  | "INVALID_FILE"
  | "INVALID_JSON"
  | "UNSUPPORTED_SCHEMA_VERSION"
  | "UNSUPPORTED_ALGORITHM"
  | "INVALID_SIGNATURE"
  | "INVALID_PAYLOAD"
  | "WRONG_MODULE"
  | "EXPIRED"
  | "INSUFFICIENT_MACHINE_INFO"
  | "MACHINE_MISMATCH"
  | "IO_ERROR"
  | "LICENSE_VERSION_NOT_MATCH";

export interface LicenseAccessSnapshot {
  licensed: boolean;
  code: LicenseStatusCode | null;
  message: string;
  verifiedAt: string;
  expiresAt: string;
  features: string[];
}

export interface LicenseIdentifierMatch {
  key: string;
  licenseInfo: string;
  currentInfo: string;
  currentAvailable: boolean;
  matched: boolean;
}

export interface LicenseStatus extends LicenseAccessSnapshot {
  licensePath: string;
  schemaVersion: number;
  keyId: string;
  algorithm: string;
  licenseId: string;
  client: string;
  moduleId: string;
  issuedAt: string;
  capturedIdentifiers: number;
  licenseIdentifierCount: number;
  currentIdentifierCount: number;
  matchedIdentifiers: number;
  requiredMatchedIdentifiers: number;
  matchRatio: number;
  identifierMatches: Record<string, LicenseIdentifierMatch>;
  warnings: string[];
  errors: string[];
}

export type RequiredLicense =
  | false
  | true
  | {
      features: readonly [string, ...string[]];
      match?: "all" | "any";
    };
