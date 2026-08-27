interface RuntimeEvaluator {
  evaluate<T>(expression: string): Promise<T>;
}

export async function updateSharedLayout(
  client: RuntimeEvaluator,
  transformSource: string,
): Promise<void> {
  const updated = await client.evaluate<boolean>(`(async () => {
    for (let attempt = 0; attempt < 3; attempt += 1) {
      const currentResponse = await fetch("/api/v1/settings/layout");
      if (!currentResponse.ok) return false;
      const current = await currentResponse.json();
      const layout = (${transformSource})(current.layout);
      const saved = await fetch("/api/v1/settings/layout", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ expectedRevision: current.revision, layout })
      });
      if (saved.ok) return true;
      if (saved.status !== 409) return false;
    }
    return false;
  })()`);
  if (!updated) {
    throw new Error("共有レイアウトを検証状態へ更新できませんでした。");
  }
}

export async function readSharedLayout<T>(client: RuntimeEvaluator): Promise<T> {
  return client.evaluate<T>(`(async () => {
    const response = await fetch("/api/v1/settings/layout");
    if (!response.ok) throw new Error("shared layout unavailable");
    return (await response.json()).layout;
  })()`);
}
