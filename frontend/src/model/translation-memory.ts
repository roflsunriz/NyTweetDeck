export interface TranslationScope {
  accountId: string;
  kind: "post" | "note";
  id: string;
  sourceLanguage: string | null;
  targetLanguage: string;
  text?: string;
}

export function translationMemoryKey(scope: TranslationScope): string {
  return JSON.stringify([
    scope.accountId,
    scope.kind,
    scope.id,
    scope.sourceLanguage?.toLowerCase().replaceAll("_", "-") ?? null,
    scope.targetLanguage.toLowerCase().replaceAll("_", "-"),
    scope.text ?? null,
  ]);
}

/** Successful translations live only for this page session; pending work is never evicted. */
export class TranslationMemory<T> {
  private readonly values = new Map<string, T>();
  private readonly pending = new Map<string, Promise<T>>();

  constructor(private readonly capacity = 500) {}

  get(key: string): T | undefined {
    const value = this.values.get(key);
    if (value !== undefined) {
      this.values.delete(key);
      this.values.set(key, value);
    }
    return value;
  }

  load(key: string, operation: () => Promise<T>, successful: (value: T) => boolean): Promise<T> {
    const cached = this.get(key);
    if (cached !== undefined) return Promise.resolve(cached);
    const existing = this.pending.get(key);
    if (existing !== undefined) return existing;
    const request = Promise.resolve()
      .then(operation)
      .then((value) => {
        if (successful(value)) {
          this.values.set(key, value);
          if (this.values.size > this.capacity) {
            const oldest = this.values.keys().next().value;
            if (oldest !== undefined) this.values.delete(oldest);
          }
        }
        return value;
      })
      .finally(() => {
        this.pending.delete(key);
      });
    this.pending.set(key, request);
    return request;
  }
}
