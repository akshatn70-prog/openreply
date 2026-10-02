import type { Metadata, Viewport } from "next";
import { Analytics } from "@vercel/analytics/next";
import "./globals.css";

export const metadata: Metadata = {
  title: "TheHomeDoc - Instagram comment-to-DM automation",
  description:
    "A free, self-hosted Instagram comment-to-DM automation tool. Send an Instagram DM automatically when someone comments a keyword on your post or reel, using the official Meta API.",
  keywords: [
    "instagram automation",
    "comment to DM",
    "instagram private replies",
    "social commerce",
    "manychat alternative",
  ],
  manifest: "/manifest.webmanifest",
  appleWebApp: {
    capable: true,
    title: "TheHomeDoc",
    statusBarStyle: "black-translucent",
  },
  icons: {
    icon: [
      { url: "/icon-192.png", sizes: "192x192", type: "image/png" },
      { url: "/icon-512.png", sizes: "512x512", type: "image/png" },
    ],
    apple: "/apple-touch-icon.png",
  },
};

export const viewport: Viewport = {
  themeColor: "#18181b",
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className="h-full dark">
      <body
        className="min-h-full bg-background text-foreground font-sans antialiased"
        style={{ paddingBottom: "env(safe-area-inset-bottom)" }}
      >
        {children}
        <Analytics />
      </body>
    </html>
  );
}