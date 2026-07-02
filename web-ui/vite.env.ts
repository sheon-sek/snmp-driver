export function requiredEnv(env: Record<string, string>, key: string) {
  const value = env[key];

  if (!value) {
    throw new Error(`Missing required env: ${key}`);
  }

  return value;
}
