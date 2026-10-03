import { createServer } from "node:http";

const port = Number(process.env.PORT || 8081);
const model = process.env.GEMINI_MODEL || "gemini-3.8-flash";
const maxImageBytes = 10 * 1024 * 1024;

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

async function testOpenRouter() {
  const mimeType = "image/jpeg";
  const imageBase64 = "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/2wBDAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAn/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwA/AB//2Q==";
  const image = Buffer.from(imageBase64, "base64");
  
  const prompt = `Read this handwritten estimate as a human estimator would read the table. This company works on aluminum windows, screens, glass, and doors. Examples of their labels include Frame, Leaf, Interlock, Net leaf, Net corners, Net fiber, Net handle, Corner carte, Glazing Rubber, Rollers, Locks, W1, and T+T. Use these only as reading hints; transcribe other item and service names as written. Pay special attention to short handwritten codes: W1 ends with the digit one, and T+T has two letter Ts (not T+I). Do not drop or substitute characters in short labels. Return only actual priced line items as structured rows. Each row has an item or service description, quantity, and unit price. Do not include headings, customer details, subtotals, tax totals, or final totals. Keep separately written fees or services (for example installation, delivery, W1, or T+T) as their own line when they appear to be charges. For a fixed charge with one amount and no quantity, use quantity "1" and that amount as unitPrice. Return numeric quantity and price strings without currency marks or thousands separators. If a quantity, description, or unit price is unclear, do not guess: leave that field empty and set needsReview true. Read the quantity and unit-price columns when present; treat the amount column only as a cross-check, and never use an amount column as a second price. Do not calculate or adjust prices. Include every legible priced item once and keep the original row order. Set confidence between 0 and 1 and needsReview true for uncertain or inferred rows.`;

  console.log("Testing OpenRouter API directly...");
  
  const upstream = await fetch("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${process.env.OPENROUTER_API_KEY || process.env.GEMINI_API_KEY}`
    },
    body: JSON.stringify({
      model: "google/gemma-4-27b-it:free",
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
  console.log("OpenRouter response status:", upstream.status);
  console.log("OpenRouter response body:", upstreamText);
  
  if (!upstream.ok) {
    console.error("OpenRouter request failed:", upstream.status);
  }
}

testOpenRouter().catch(console.error);