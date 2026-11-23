import type { ReactNode } from "react";

import { ApiError } from "../api/client";
import { useMe } from "../api/hooks";
import type { Me } from "../api/types";
import { Button, FullPageMessage } from "../components/ui";
import { leavingPage, signIn } from "./session";

/**
 * Renders the app only for a signed-in user. "Signed in" means the API has a session for this
 * browser, which we learn by asking for /api/me. Without one that call gets 401, and apiFetch
 * sends the browser to sign in.
 */
export function AuthGate({ children }: { children: (me: Me) => ReactNode }) {
  // The API sends the browser here when Keycloak's answer was unusable. Asking again
  // automatically could loop forever, so the user decides.
  if (new URLSearchParams(window.location.search).get("signin") === "failed") {
    return (
      <FullPageMessage title="Sign-in failed" message="We couldn't complete your sign-in. Please try again.">
        <Button onClick={() => signIn("/")}>Try again</Button>
      </FullPageMessage>
    );
  }
  return <SessionGate>{children}</SessionGate>;
}

function SessionGate({ children }: { children: (me: Me) => ReactNode }) {
  const me = useMe();

  if (me.isSuccess) {
    return <>{children(me.data)}</>;
  }
  if (me.error && !(me.error instanceof ApiError && me.error.status === 401)) {
    return (
      <FullPageMessage title="Can't reach the ERP" message={me.error.message}>
        <Button onClick={() => void me.refetch()}>Try again</Button>
      </FullPageMessage>
    );
  }
  return <FullPageMessage title={leavingPage() === "signout" ? "Signing you out…" : "Signing you in…"} busy />;
}
