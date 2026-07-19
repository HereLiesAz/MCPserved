import { createInterface } from "node:readline/promises";
import qrcode from "qrcode-terminal";
import { generateKeyPair } from "./crypto.js";
import { saveConfig, CONFIG_PATH } from "./config.js";

/**
 * One-time pairing between this machine and a device.
 *
 * Both public keys travel out of band, by QR code, in both directions. Passing
 * the server's key through the relay would be simpler and would also make the
 * relay able to substitute its own key during the one exchange that establishes
 * trust — which is the definition of a man in the middle. A camera and a
 * terminal are slower and are not.
 *
 * The exchange establishes only that the two endpoints share a secret. It says
 * nothing about authority: what the device will actually permit is decided
 * afterwards, per package, in the grants screen.
 */
export async function pair(): Promise<void> {
  const rl = createInterface({ input: process.stdin, output: process.stdout });

  console.log("\nOpen MCPserved on the device, tap Pair, and scan nothing yet.");
  console.log("Paste the string under the device's QR code here.\n");

  const payload = (await rl.question("device payload: ")).trim();
  const parts = payload.split(":");

  if (parts.length !== 5 || parts[0] !== "mcpserved" || parts[1] !== "1") {
    rl.close();
    throw new Error("that is not an MCPserved v1 pairing payload");
  }

  const deviceId = parts[2];
  const relayUrl = Buffer.from(parts[3], "base64url").toString("utf8");
  const devicePublicKey = Buffer.from(parts[4], "base64url");

  if (devicePublicKey.length !== 32) {
    rl.close();
    throw new Error("device public key is not 32 bytes");
  }

  const { privateKey, publicKey } = generateKeyPair();

  saveConfig({
    deviceId,
    relayUrl,
    serverPrivateKey: privateKey.toString("base64"),
    devicePublicKey: devicePublicKey.toString("base64"),
  });

  console.log(`\nPaired. Written to ${CONFIG_PATH}\n`);
  console.log("Now scan this with the device to complete the exchange:\n");

  // Same envelope shape the device emits, so one scanner handles both directions.
  const reply = [
    "mcpserved",
    "1",
    deviceId,
    Buffer.from(relayUrl, "utf8").toString("base64url"),
    publicKey.toString("base64url"),
  ].join(":");

  qrcode.generate(reply, { small: true });
  console.log(`\n${reply}\n`);
  console.log("Device shows 'Paired' when it has the key.\n");

  rl.close();
}
