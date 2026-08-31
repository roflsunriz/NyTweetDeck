import { describe, expect, test } from "bun:test";

import { isAllowedLoopbackRequest } from "./offline-sandbox-policy";

describe("Xオフラインsandbox通信ポリシー", () => {
  test("割り当てたloopback serverだけを許可する", () => {
    expect(isAllowedLoopbackRequest("http://127.0.0.1:18081/sandbox.html", 18081)).toBeTrue();
    expect(isAllowedLoopbackRequest("http://127.0.0.1:18081/mock/timeline.json", 18081)).toBeTrue();
  });

  test("別port、localhost、外部HTTP、HTTPS、userinfoを拒否する", () => {
    expect(isAllowedLoopbackRequest("http://127.0.0.1:18082/", 18081)).toBeFalse();
    expect(isAllowedLoopbackRequest("http://localhost:18081/", 18081)).toBeFalse();
    expect(isAllowedLoopbackRequest("http://example.com:18081/", 18081)).toBeFalse();
    expect(isAllowedLoopbackRequest("https://127.0.0.1:18081/", 18081)).toBeFalse();
    expect(isAllowedLoopbackRequest("http://user@127.0.0.1:18081/", 18081)).toBeFalse();
  });
});
