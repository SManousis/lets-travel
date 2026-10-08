import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { ManagerProfile } from "./features/managers/ManagerProfile";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const payload = `<img src=x onerror="window.hacked=1"><script>window.hacked=1</script>`;

it("renders text written by users as text, never as markup (stored XSS)", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
    managerId: "m1", displayName: payload, memberSince: "2026-01-01T00:00:00Z", completedTravels: 1, totalTravels: 1,
    averageRating: 4, ratingCount: 1, reportCount: 0,
    recentRatings: [{ travelTitle: payload, rating: 4, comment: payload, createdAt: "2026-05-01T00:00:00Z" }],
  }), { status: 200, headers: { "Content-Type": "application/json" } })));

  const { container } = render(<ManagerProfile context={{ token: "t", onUnauthorized: () => {} }} managerId="m1" />);
  expect((await screen.findAllByText(payload)).length).toBeGreaterThan(0);
  expect(container.querySelector("img, script")).toBeNull();
  expect((window as unknown as { hacked?: number }).hacked).toBeUndefined();
});

it("uses no API that turns strings into markup or code (DOM XSS)", () => {
  const files: string[] = [];
  const walk = (dir: string) => {
    for (const name of readdirSync(dir)) {
      const path = join(dir, name);
      if (statSync(path).isDirectory()) walk(path);
      else if (/\.(ts|tsx)$/.test(name) && !name.endsWith(".test.tsx") && !name.endsWith(".test.ts")) files.push(path);
    }
  };
  walk(join(process.cwd(), "src"));
  const sinks = /dangerouslySetInnerHTML|\.innerHTML|\.outerHTML|insertAdjacentHTML|document\.write|\beval\(|new Function\(/;
  const offenders = files.filter(file => sinks.test(readFileSync(file, "utf8")));
  expect(files.length).toBeGreaterThan(10);
  expect(offenders).toEqual([]);
});
