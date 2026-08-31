export function isAllowedLoopbackRequest(value: string, port: number): boolean {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return false;
  }
  return (
    url.protocol === "http:" &&
    url.hostname === "127.0.0.1" &&
    url.port === String(port) &&
    url.username === "" &&
    url.password === ""
  );
}
