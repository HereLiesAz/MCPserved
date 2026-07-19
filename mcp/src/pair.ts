import { createInterface } from "node:readline/promises";
import qrcode from "qrcode-terminal";
import { generateKeyPair } from "./crypto.js";
import { saveConfig, CONFIG_PATH } from "./config.js";

/**
 * One-time pairing between this machine and a device, for the app backend.
 *
 * Both public keys travel out of band, by QR code, in both directions, so no
 * third party ever sits in the exchange that establishes trust. The shared
 * secret then authenticates this server when it later connects to the device's
 * loopback control port over `adb forward`.
 *
 * Pairing is only needed for the richer app backend. The quick-connect adb
 * backend needs none of this — it just needs `adb` to reach the device.
 *
 * The exchange establishes only that the two endpoints share a secret. It says
 * nothing about authority: what the device will actually permit is decided
 * afterwards, per package, in the grants screen.
 */
export async function pair(): Promise<void> {
  const rl = createInterface({ input: process.stdin, output: process.stdout });

  console.log("\nOpen MCPserved on the device, go to Pair, and read the string under its QR code.");
  console.log("Paste it here.\n");

  const payload = (await rl.question("device payload: ")).trim();
  const parts = payload.split(":");

  if (parts.length !== 4 || parts[0] !== "mcpserved" || parts[1] !== "2") {
    rl.close();
    throw new Error("that is not an MCPserved v2 pairing payload");
  }

  const deviceId = parts[2];
  const devicePublicKey = Buffer.from(parts[3], "base64url");

  if (devicePublicKey.length !== 32) {
    rl.close();
    throw new Error("device public key is not 32 bytes");
  }

  const { privateKey, publicKey } = generateKeyPair();

  saveConfig({
    deviceId,
    serverPrivateKey: privateKey.toString("base64"),
    devicePublicKey: devicePublicKey.toString("base64"),
  });

  console.log(`\nPaired. Written to ${CONFIG_PATH}\n`);
  console.log("Now scan this with the device to complete the exchange:\n");

  // Same envelope shape the device emits, so one scanner handles both directions.
  const reply = ["mcpserved", "2", deviceId, publicKey.toString("base64url")].join(":");

  qrcode.generate(reply, { small: true });
  console.log(`\n${reply}\n`);
  console.log("The device shows 'Paired' once it has the key.");
  console.log(
    "Then arm the service on the device and make sure it is reachable over adb\n" +
      "(`adb devices`, or `adb connect <ip>:5555` for Wi-Fi). The server sets up the\n" +
      "`adb forward` tunnel itself when it connects.\n",
  );

  rl.close();
}
