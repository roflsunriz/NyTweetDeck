import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { XApiSetup } from "./x-api-setup";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

describe("Android API setup", () => {
  test("loads readiness and saves a validated device profile", async () => {
    const requests: Array<{ url: string; method: string; body: string | null }> = [];
    globalThis.fetch = (async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      requests.push({ url, method, body: typeof init?.body === "string" ? init.body : null });
      if (url.endsWith("/readiness")) {
        return Response.json({
          androidApiVersion: "12.19.1-release.0",
          clientCredentialsAvailable: false,
          deviceProfileAvailable: false,
        });
      }
      if (method === "PUT") {
        return Response.json({
          ...(JSON.parse(String(init?.body)) as object),
          schemaVersion: 1,
          clientUuid: "12345678-1234-1234-1234-123456789012",
          deviceId: "12345678901234567890123456789012",
        });
      }
      return Response.json(null);
    }) as typeof fetch;
    const user = userEvent.setup();
    render(<XApiSetup translation={translate("ja")} />);

    await waitFor(() => expect(screen.getByText("12.19.1-release.0")).toBeDefined());
    expect(screen.getAllByText("未設定")).toHaveLength(2);

    await user.type(screen.getByLabelText("端末モデル"), "Pixel Test");
    await user.type(screen.getByLabelText("Androidバージョン"), "16");
    await user.type(screen.getByLabelText("メーカー"), "Google");
    await user.type(screen.getByLabelText("ブランド"), "google");
    await user.type(screen.getByLabelText("製品コード"), "test_product");
    await user.type(screen.getByLabelText("セキュリティパッチ日"), "2026-08-05");
    await user.click(screen.getByRole("button", { name: "端末プロファイルを保存" }));

    await waitFor(() => expect(screen.getByText("保存しました")).toBeDefined());
    const saveRequest = requests.find((request) => request.method === "PUT");
    expect(saveRequest?.url).toBe("/api/v1/x-api/device-profile");
    expect(saveRequest?.body).toContain('"model":"Pixel Test"');
  });
});
