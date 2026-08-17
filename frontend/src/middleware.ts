import { NextRequest, NextResponse } from "next/server";

const PROTECTED_PREFIXES = [
  "/messages",
  "/saved",
  "/agreements",
  "/profile",
  "/settings",
  "/my-listings",
  "/notifications",
  "/admin",
  "/onboarding",
];

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const isProtected = PROTECTED_PREFIXES.some((p) => pathname.startsWith(p));
  if (!isProtected) {
    return NextResponse.next();
  }
  const hasSession = request.cookies.has("fm_token");
  if (!hasSession) {
    const signin = new URL("/signin", request.url);
    signin.searchParams.set("next", pathname);
    return NextResponse.redirect(signin);
  }
  return NextResponse.next();
}

export const config = {
  matcher: [
    "/messages/:path*",
    "/saved/:path*",
    "/agreements/:path*",
    "/profile/:path*",
    "/settings/:path*",
    "/my-listings/:path*",
    "/notifications/:path*",
    "/admin/:path*",
    "/onboarding/:path*",
  ],
};
