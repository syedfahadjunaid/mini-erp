/**
 * Sign-in and sign-out, handled by the API acting as a backend-for-frontend.
 *
 * The SPA never sees an OAuth2 token. Signing in is a full-page visit to the API, which runs the
 * authorization-code flow with Keycloak (as a confidential client, with PKCE) and gives the
 * browser an HttpOnly session cookie that JavaScript can't read. API calls go to this same
 * origin, and the browser sends the cookie.
 *
 * Because the browser sends that cookie automatically, the API also requires a CSRF token on
 * every state-changing request: it sets a readable cookie, and we copy it into a header, which
 * a page on another site cannot do.
 */

/** Where the API starts a sign-in (Spring Security's authorization endpoint). */
export const SIGN_IN_URL = "/oauth2/authorization/keycloak";
const CSRF_COOKIE = "ERP-XSRF-TOKEN";
const RETURN_TO_KEY = "erp.returnTo";

/** Page navigation, as an object so tests can replace it (jsdom can't really navigate). */
export const browser = {
  assign: (url: string) => window.location.assign(url),
};

/**
 * Set once we've started sending the browser away, so a burst of 401s causes one redirect, and
 * signing out isn't cancelled by a sign-in that some failing request triggers.
 */
let leavingFor: "signin" | "signout" | null = null;

export function leavingPage() {
  return leavingFor;
}

/** Sends the browser to sign in, remembering which page the user was on. */
export function signIn(returnTo = window.location.pathname + window.location.search) {
  if (leavingFor) return;
  leavingFor = "signin";
  try {
    sessionStorage.setItem(RETURN_TO_KEY, safeReturnTo(returnTo));
  } catch {
    // Storage blocked: the user lands on the home page instead.
  }
  browser.assign(SIGN_IN_URL);
}

/**
 * Ends the app session, then the Keycloak session too (otherwise the next sign-in would succeed
 * without a password). The API answers with Keycloak's logout URL; going there brings the user
 * back, signed out.
 */
export async function signOut() {
  if (leavingFor) return;
  leavingFor = "signout";
  try {
    const response = await fetch("/logout", { method: "POST", headers: csrfHeaders() });
    if (!response.ok) throw new Error(`Sign-out failed (${response.status})`);
    const { logoutUrl } = (await response.json()) as { logoutUrl?: string };
    browser.assign(logoutUrl ?? "/");
  } catch (error) {
    leavingFor = null;
    throw error;
  }
}

/** The header the API checks on POST/PUT (the "double-submit cookie" pattern). */
export function csrfHeaders(): Record<string, string> {
  const prefix = `${CSRF_COOKIE}=`;
  const cookie = document.cookie.split("; ").find((c) => c.startsWith(prefix));
  return cookie ? { "X-XSRF-TOKEN": decodeURIComponent(cookie.slice(prefix.length)) } : {};
}

/** Call once at start-up: after signing in the API lands on "/", so go back to where the user was. */
export function restorePageAfterSignIn() {
  let stored: string | null;
  try {
    stored = sessionStorage.getItem(RETURN_TO_KEY);
    sessionStorage.removeItem(RETURN_TO_KEY);
  } catch {
    return;
  }
  const returnTo = safeReturnTo(stored);
  if (returnTo !== "/" && window.location.pathname === "/" && !window.location.search) {
    window.history.replaceState(null, "", returnTo);
  }
}

/** Only same-site paths may be a post-login destination; anything else would be an open redirect. */
export function safeReturnTo(path: unknown): string {
  if (typeof path !== "string" || !path.startsWith("/") || path.startsWith("//") || path.includes("\\")) {
    return "/";
  }
  return path;
}

/** Test hook: forget that we were leaving the page. */
export function resetForTests() {
  leavingFor = null;
}
