import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const distDir = path.resolve(__dirname, "../dist");
const targetDir = path.resolve(__dirname, "../../src/main/resources/static");

if (fs.existsSync(distDir)) {
  fs.mkdirSync(targetDir, { recursive: true });
  fs.cpSync(distDir, targetDir, { recursive: true });
  console.log("✓ Successfully synced frontend build to src/main/resources/static");
} else {
  console.warn("⚠ dist directory not found at", distDir);
}
