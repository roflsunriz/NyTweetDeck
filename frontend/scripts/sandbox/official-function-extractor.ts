export function extractNamedFunction(source: string, name: string, startAt = 0): string {
  if (!/^[a-zA-Z_$][a-zA-Z0-9_$]*$/.test(name)) {
    throw new Error(`JavaScript関数名が不正です: ${name}`);
  }
  const marker = `function ${name}(`;
  const functionStart = source.indexOf(marker, startAt);
  if (functionStart < 0) {
    throw new Error(`公式関数 ${name} を特定できませんでした。`);
  }
  const bodyStart = source.indexOf("{", functionStart + marker.length);
  if (bodyStart < 0) {
    throw new Error(`公式関数 ${name} の本体を特定できませんでした。`);
  }

  let depth = 0;
  let quote: '"' | "'" | "`" | null = null;
  let escaped = false;
  for (let index = bodyStart; index < source.length; index += 1) {
    const character = source[index];
    if (quote !== null) {
      if (escaped) {
        escaped = false;
      } else if (character === "\\") {
        escaped = true;
      } else if (character === quote) {
        quote = null;
      }
      continue;
    }
    if (character === '"' || character === "'" || character === "`") {
      quote = character;
      continue;
    }
    if (character === "{") {
      depth += 1;
    } else if (character === "}") {
      depth -= 1;
      if (depth === 0) {
        return source.slice(functionStart, index + 1);
      }
    }
  }
  throw new Error(`公式関数 ${name} の終端を特定できませんでした。`);
}
