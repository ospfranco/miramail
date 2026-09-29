import {
  GoogleSignin,
  isErrorWithCode,
  isSuccessResponse,
  statusCodes,
  type User,
} from '@react-native-google-signin/google-signin';

// OAuth client IDs are public identifiers, not secrets.
// Android has no client ID here: Google matches the Android client by package name + SHA-1.
const IOS_CLIENT_ID =
  '760054649215-log9rqaj7h239g6lr9qsink2adnla6i2.apps.googleusercontent.com';

export const GMAIL_SCOPES = [
  'https://www.googleapis.com/auth/gmail.modify',
  'https://www.googleapis.com/auth/gmail.send',
];

GoogleSignin.configure({
  iosClientId: IOS_CLIENT_ID,
  scopes: GMAIL_SCOPES,
});

/** Restores a previous session without UI. Returns null if the user never signed in. */
export async function restoreSession(): Promise<User | null> {
  const res = await GoogleSignin.signInSilently();
  return res.type === 'success' ? res.data : null;
}

/** Shows Google's sign-in UI. Returns null if the user cancelled. */
export async function signIn(): Promise<User | null> {
  try {
    await GoogleSignin.hasPlayServices();
    const res = await GoogleSignin.signIn();
    return isSuccessResponse(res) ? res.data : null;
  } catch (e) {
    if (isErrorWithCode(e) && e.code === statusCodes.IN_PROGRESS) return null;
    throw e;
  }
}

export async function signOut() {
  await GoogleSignin.signOut();
}

// GoogleSignin.getTokens() rejects if called while another call is in flight,
// so concurrent callers share a single pending request.
let pendingToken: Promise<string> | null = null;

/** Returns a valid access token. Pass `forceRefresh` after a 401 to discard the cached one. */
export function getAccessToken(forceRefresh = false): Promise<string> {
  pendingToken ??= fetchAccessToken(forceRefresh).finally(() => {
    pendingToken = null;
  });
  return pendingToken;
}

async function fetchAccessToken(forceRefresh: boolean): Promise<string> {
  if (forceRefresh) {
    const { accessToken } = await GoogleSignin.getTokens();
    await GoogleSignin.clearCachedAccessToken(accessToken);
  }
  const { accessToken } = await GoogleSignin.getTokens();
  return accessToken;
}
