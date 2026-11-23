import { Link } from "react-router";

import { EmptyState } from "../components/ui";

export function NotFoundPage() {
  return (
    <EmptyState>
      There's no page here. <Link to="/" className="text-teal-800 hover:underline">Back to the dashboard</Link>
    </EmptyState>
  );
}
