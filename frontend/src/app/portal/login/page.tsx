import { redirect } from "next/navigation";

// There is one login page for everyone; old portal links end up there.
export default function PortalLoginRedirect() {
  redirect("/login");
}
