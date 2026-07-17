// Parses a response body defensively: an empty or non-JSON body (e.g. a 404 for an
// endpoint the running backend doesn't have yet) returns null instead of throwing the
// cryptic "Unexpected end of JSON input".
export async function readJson(res: Response): Promise<any> {
  const text = await res.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
