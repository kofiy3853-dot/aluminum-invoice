import { createServer } from "node:http";
import { timingSafeEqual } from "node:crypto";

const port = Number(process.env.PORT || 8081);
const defaultModel = process.env.OPENROUTER_MODEL || process.env.GEMINI_MODEL || "stealth/space-bunny-alpha";
const maxImageBytes = 10 * 1024 * 1024;
const requestWindowMs = 60_000;
const maxRequestsPerWindow = 10;
const maxConcurrentExtractions = 2;
let requestWindowStartedAt = Date.now();
let requestsInWindow = 0;
let activeExtractions = 0;

const responseSchema = {
  type: "object",
  properties: {
    items: {
      type: "array",
      items: {
        type: "object",
        properties: {
          description: { type: "string", description: "The written item or service name." },
          quantity: { type: "string", description: "The written quantity, or 1 for a single fixed charge. Leave empty if unreadable." },
          unitPrice: { type: "string", description: "The written price per unit. Leave empty if unreadable." },
          confidence: { type: "number", description: "Confidence from 0 to 1 in reading this row." },
          needsReview: { type: "boolean", description: "True when any field is unclear or inferred." }
        },
        required: ["description", "quantity", "unitPrice", "confidence", "needsReview"]
      }
    }
  },
  required: ["items"]
};

function sendJson(response, statusCode, body) {
  response.writeHead(statusCode, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  response.end(JSON.stringify(body));
}

async function readBody(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > maxImageBytes * 1.5) throw new Error("Request is too large. Use a smaller photo.");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

const server = createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    sendJson(response, 200, { ok: true });
    return;
  }
  if (request.method !== "POST" || request.url !== "/extract") {
    sendJson(response, 404, { error: "Route not found." });
    return;
  }
  const expectedAccessToken = process.env.APP_ACCESS_TOKEN;
  if (expectedAccessToken) {
    const authorization = request.headers.authorization || "";
    const suppliedAccessToken = authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
    const expected = Buffer.from(expectedAccessToken);
    const supplied = Buffer.from(suppliedAccessToken);
    if (expected.length !== supplied.length || !timingSafeEqual(expected, supplied)) {
      sendJson(response, 401, { error: "This app is not authorized to use the estimate reader." });
      return;
    }
  }
  const now = Date.now();
  if (now - requestWindowStartedAt >= requestWindowMs) {
    requestWindowStartedAt = now;
    requestsInWindow = 0;
  }
  if (requestsInWindow >= maxRequestsPerWindow || activeExtractions >= maxConcurrentExtractions) {
    sendJson(response, 429, { error: "The estimate reader is busy. Please wait a moment and try again." });
    return;
  }
  requestsInWindow += 1;
  const apiKey = process.env.OPENROUTER_API_KEY || process.env.GEMINI_API_KEY;
  if (!apiKey) {
    sendJson(response, 503, { error: "The estimate reader is not configured yet. Set OPENROUTER_API_KEY on the server." });
    return;
  }

  activeExtractions += 1;
  try {
    const body = await readBody(request);
    const mimeType = String(body.mimeType || "");
    const imageBase64 = String(body.imageBase64 || "");
    if (!["image/jpeg", "image/png", "image/webp"].includes(mimeType)) {
      sendJson(response, 400, { error: "Choose a JPG, PNG, or WEBP image." });
      return;
    }
    const image = Buffer.from(imageBase64, "base64");
    if (!image.length || image.length > maxImageBytes) {
      sendJson(response, 400, { error: "Photo is empty or too large. Choose a photo under 10 MB." });
      return;
    }

    const prompt = `Read this handwritten estimate as a human estimator would read the table. This company works on aluminum windows, screens, glass, and doors. Examples of their labels include Frame, Leaf, Interlock, Net leaf, Net corners, Net fiber, Net handle, Corner carte, Glazing Rubber, Rollers, Locks, W1, and T+T. Use these only as reading hints; transcribe other item and service names as written. Pay special attention to short handwritten codes: W1 ends with the digit one, and T+T has two letter Ts (not T+I). Do not drop or substitute characters in short labels. Return only actual priced line items as structured rows. Each row has an item or service description, quantity, and unit price. Do not include headings, customer details, subtotals, tax totals, or final totals. Keep separately written fees or services (for example installation, delivery, W1, or T+T) as their own line when they appear to be charges. For a fixed charge with one amount and no quantity, use quantity "1" and that amount as unitPrice. Return numeric quantity and price strings without currency marks or thousands separators. If a quantity, description, or unit price is unclear, do not guess: leave that field empty and set needsReview true. Read the quantity and unit-price columns when present; treat the amount column only as a cross-check, and never use an amount column as a second price. Do not calculate or adjust prices. Include every legible priced item once and keep the original row order. Set confidence between 0 and 1 and needsReview true for uncertain or inferred rows.`;

    const upstream = await fetch("https://openrouter.ai/api/v1/chat/completions", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${apiKey}`
      },
      body: JSON.stringify({
        model: defaultModel,
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: prompt + "\n\nYou MUST return ONLY valid JSON matching this schema: " + JSON.stringify(responseSchema) },
              { type: "image_url", image_url: { url: `data:${mimeType};base64,${image.toString("base64")}` } }
            ]
          }
        ],
        response_format: { type: "json_object" }
      }),
      signal: AbortSignal.timeout(90000)
    });
    const upstreamText = await upstream.text();
    const upstreamBody = JSON.parse(upstreamText || "{}");
    if (!upstream.ok) {
      console.error("OpenRouter request failed with status:", upstream.status);
      sendJson(response, upstream.status === 429 ? 429 : 502, { error: "The estimate reader could not process this photo. Please try again." });
      return;
    }

    let text = upstreamBody.choices?.[0]?.message?.content;
    if (!text) {
      sendJson(response, 502, { error: "The estimate reader returned no rows. Try a sharper, brighter photo." });
      return;
    }
    text = text.replace(/^```json\s*/i, "").replace(/^```\s*/i, "").replace(/\s*```$/, "").trim();
    const extracted = JSON.parse(text);
    const commonLabelCorrections = new Map([["w", "W1"], ["t+i", "T+T"], ["corner carte", "Corner carte"]]);
    const items = Array.isArray(extracted.items) ? extracted.items
      .filter(item => item && typeof item.description === "string" && item.description.trim())
      .map(item => {
        const rawLabel = item.description.trim();
        const labelKey = rawLabel.toLowerCase();
        const confidence = Number.isFinite(item.confidence) ? Math.max(0, Math.min(1, item.confidence)) : 0;
        const quantity = typeof item.quantity === "string" ? item.quantity.trim() : "";
        const unitPrice = typeof item.unitPrice === "string" ? item.unitPrice.trim() : "";
        const isKnownCornerCarte = labelKey === "corner carte" && confidence >= 0.85;
        return {
          description: commonLabelCorrections.get(labelKey) || rawLabel,
          quantity,
          unitPrice,
          confidence,
          needsReview: !quantity || !unitPrice || confidence < 0.75 || (item.needsReview === true && !isKnownCornerCarte)
        };
      }) : [];
    sendJson(response, 200, { items });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    const status = message.includes("too large") ? 413 : 400;
    if (status !== 413) console.error("Extraction failed:", message);
    sendJson(response, status, { error: status === 413 ? message : "Could not read the photo. Check the connection and try again." });
  } finally {
    activeExtractions -= 1;
  }
});

server.listen(port, "0.0.0.0", () => {
  console.log(`Who Knows estimate reader listening on port ${port}`);
});
